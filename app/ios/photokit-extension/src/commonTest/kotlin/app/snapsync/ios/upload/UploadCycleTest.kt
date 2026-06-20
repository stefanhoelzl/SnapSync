package app.snapsync.ios.upload

import app.snapsync.engine.LedgerState
import app.snapsync.engine.LedgerWriter
import app.snapsync.engine.Resource
import app.snapsync.engine.SyncEngine
import app.snapsync.engine.UploadError
import app.snapsync.engine.UploadRequest
import app.snapsync.engine.UploadRequestProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadCycleTest {

    /** A no-network provider returning a throwaway destination — the cycle never inspects the URL. */
    private class StubUploadRequestProvider : UploadRequestProvider {
        override suspend fun provide(resource: Resource): UploadRequest =
            UploadRequest(url = "https://stub.invalid/${resource.filename}", headers = emptyMap(), resource = resource)
    }

    /** Records what the cycle asked the platform to do; serves canned discovered/returned jobs. */
    private class FakePlatform(
        private val discovered: List<Resource> = emptyList(),
        private val retryJobs: List<PlatformUploadJob> = emptyList(),
        private val ackJobs: List<PlatformUploadJob> = emptyList(),
        private val nextToken: ByteArray = byteArrayOf(9),
        private val limitAfter: Int = Int.MAX_VALUE,
    ) : UploadJobPlatform {
        val created = mutableListOf<Resource>()
        val retried = mutableListOf<PlatformUploadJob>()
        val acknowledged = mutableListOf<PlatformUploadJob>()
        var discoverTokenArg: ByteArray? = null
        private var creates = 0

        override suspend fun fetchRetryJobs() = retryJobs
        override suspend fun fetchAckJobs() = ackJobs
        override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) { retried += job }
        override suspend fun acknowledge(job: PlatformUploadJob) { acknowledged += job }
        override suspend fun discoverResources(sinceToken: ByteArray?): Discovery {
            discoverTokenArg = sinceToken
            return Discovery(discovered, nextToken)
        }
        override suspend fun createJob(request: UploadRequest, resource: Resource): CreateResult {
            if (creates >= limitAfter) return CreateResult.LIMIT_EXCEEDED
            creates++
            created += resource
            return CreateResult.CREATED
        }
    }

    private class FakeStore(private val token: ByteArray? = null) : DiscoveryStore {
        var saved: ByteArray? = null
        override fun loadToken(): ByteArray? = token
        override fun saveToken(token: ByteArray) { saved = token }
    }

    private fun resource(name: String, version: String = "v1") =
        Resource(filename = name, contentType = "image/jpeg", version = version, metadata = emptyMap(), data = Unit)

    private fun platformJob(key: String, state: PlatformJobState, error: UploadError? = null) =
        PlatformUploadJob(key = key, contentType = "image/jpeg", state = state, error = error, data = Unit, handle = Unit)

    private fun cycleOver(
        backend: InMemoryLedgerBackend,
        platform: FakePlatform,
        store: DiscoveryStore = FakeStore(),
    ): UploadCycle {
        val ledger = LedgerWriter(backend)
        return UploadCycle(SyncEngine(StubUploadRequestProvider(), ledger), ledger, platform, store)
    }

    @Test
    fun discovery_creates_a_job_per_new_resource_and_records_requested_after_create() = runTest {
        val backend = InMemoryLedgerBackend()
        val platform = FakePlatform(discovered = listOf(resource("a"), resource("b")))
        val store = FakeStore()

        val result = cycleOver(backend, platform, store).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf("a", "b"), platform.created.map { it.filename })
        assertEquals(LedgerState.REQUESTED, backend.get("a")?.state)
        assertEquals(LedgerState.REQUESTED, backend.get("b")?.state)
        assertContentEquals(byteArrayOf(9), store.saved) // cursor advanced on a fully-drained cycle
    }

    @Test
    fun discovery_skips_in_flight_and_completed_resources() = runTest {
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordRequested("a", attempt = 0, version = "v1") // in flight
        LedgerWriter(backend).recordCompleted("b", attempt = 0, version = "v1") // done
        val platform = FakePlatform(discovered = listOf(resource("a"), resource("b")))

        cycleOver(backend, platform).run()

        assertTrue(platform.created.isEmpty(), "in-flight (REQUESTED) and COMPLETED keys must be skipped")
    }

    @Test
    fun discovery_re_uploads_when_the_version_changed() = runTest {
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordCompleted("a", attempt = 0, version = "v1")
        val platform = FakePlatform(discovered = listOf(resource("a", version = "v2")))

        cycleOver(backend, platform).run()

        assertEquals(listOf("a"), platform.created.map { it.filename })
    }

    @Test
    fun discovery_passes_the_loaded_cursor_to_the_platform() = runTest {
        val backend = InMemoryLedgerBackend()
        val platform = FakePlatform()
        val store = FakeStore(token = byteArrayOf(7))

        cycleOver(backend, platform, store).run()

        assertContentEquals(byteArrayOf(7), platform.discoverTokenArg)
    }

    @Test
    fun succeeded_job_records_completed_and_is_acknowledged() = runTest {
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordRequested("a", attempt = 0, version = "v1")
        val job = platformJob("a", PlatformJobState.SUCCEEDED)
        val platform = FakePlatform(ackJobs = listOf(job))

        cycleOver(backend, platform).run()

        assertEquals(LedgerState.COMPLETED, backend.get("a")?.state)
        assertEquals(listOf(job), platform.acknowledged)
        assertTrue(platform.created.isEmpty())
    }

    @Test
    fun first_failure_retries_with_a_fresh_url_and_records_requested() = runTest {
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordRequested("a", attempt = 0, version = "v1")
        val job = platformJob("a", PlatformJobState.FAILED, UploadError.Network)
        val platform = FakePlatform(retryJobs = listOf(job))

        cycleOver(backend, platform).run()

        assertEquals(listOf(job), platform.retried)
        val entry = backend.get("a")
        assertEquals(LedgerState.REQUESTED, entry?.state) // UploadStarted recorded the retry
        assertEquals(1, entry?.attempt)
        assertTrue(platform.created.isEmpty(), "a free retry re-points, it does not create")
    }

    @Test
    fun retry_spent_failure_re_creates_from_the_job_resource_and_is_acknowledged() = runTest {
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordRequested("a", attempt = 0, version = "v1")
        val job = platformJob("a", PlatformJobState.FAILED, UploadError.Network)
        val platform = FakePlatform(ackJobs = listOf(job))

        val result = cycleOver(backend, platform).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf("a"), platform.created.map { it.filename }) // re-created
        assertEquals(listOf(job), platform.acknowledged) // acked only after the re-create succeeded
        assertEquals(LedgerState.REQUESTED, backend.get("a")?.state)
        assertEquals(1, backend.get("a")?.attempt)
    }

    @Test
    fun already_completed_re_handed_job_is_a_noop_acknowledge() = runTest {
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordCompleted("a", attempt = 0, version = "v1")
        val job = platformJob("a", PlatformJobState.FAILED, UploadError.Network)
        val platform = FakePlatform(ackJobs = listOf(job))

        cycleOver(backend, platform).run()

        assertEquals(listOf(job), platform.acknowledged)
        assertTrue(platform.created.isEmpty(), "an already-COMPLETED key is not re-created")
        assertEquals(LedgerState.COMPLETED, backend.get("a")?.state)
    }

    @Test
    fun cap_during_discovery_does_not_advance_the_cursor_and_returns_processing() = runTest {
        val backend = InMemoryLedgerBackend()
        val platform = FakePlatform(
            discovered = listOf(resource("a"), resource("b"), resource("c")),
            limitAfter = 2,
        )
        val store = FakeStore()

        val result = cycleOver(backend, platform, store).run()

        assertEquals(CycleResult.PROCESSING, result)
        assertEquals(listOf("a", "b"), platform.created.map { it.filename })
        assertNull(store.saved, "cursor must NOT advance on a cap-truncated cycle")
    }

    @Test
    fun cap_during_re_create_still_acknowledges_and_returns_processing() = runTest {
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordRequested("a", attempt = 0, version = "v1")
        val job = platformJob("a", PlatformJobState.FAILED, UploadError.Network)
        val platform = FakePlatform(ackJobs = listOf(job), limitAfter = 0)
        val store = FakeStore()

        val result = cycleOver(backend, platform, store).run()

        assertEquals(CycleResult.PROCESSING, result)
        // Every presented job is acknowledged (else the system errors 50008); rediscovery retries it.
        assertEquals(listOf(job), platform.acknowledged)
        assertNull(store.saved, "cursor must NOT advance on a cap-truncated cycle")
    }
}
