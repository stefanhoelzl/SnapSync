package app.snapsync.ios.upload

import app.snapsync.engine.LedgerState
import app.snapsync.engine.LedgerWriter
import app.snapsync.engine.Resource
import app.snapsync.engine.SyncEngine
import app.snapsync.engine.UploadRequest
import app.snapsync.engine.UploadRequestProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UploadCycleTest {

    /** A no-network provider returning a throwaway destination — the cycle never inspects the URL. */
    private class StubUploadRequestProvider : UploadRequestProvider {
        override suspend fun provide(resource: Resource): UploadRequest =
            UploadRequest(url = "https://stub.invalid/${resource.filename}", headers = emptyMap(), resource = resource)
    }

    /** Records what the cycle asked the platform to do, and serves canned discovered resources. */
    private class FakePlatform(private val discovered: List<Resource>) : UploadJobPlatform {
        val created = mutableListOf<Resource>()
        var drained = false
        override suspend fun drainJobs() {
            drained = true
        }
        override suspend fun discoverResources(): List<Resource> = discovered
        override suspend fun createJob(request: UploadRequest, resource: Resource) {
            created += resource
        }
    }

    private fun resource(name: String, version: String = "v1") =
        Resource(
            filename = name,
            contentType = "image/jpeg",
            version = version,
            metadata = emptyMap(),
            data = Unit,
        )

    private fun engineOver(backend: InMemoryLedgerBackend) =
        SyncEngine(StubUploadRequestProvider(), LedgerWriter(backend))

    @Test
    fun drains_then_creates_a_job_per_new_resource_and_records_requested() = runTest {
        val backend = InMemoryLedgerBackend()
        val platform = FakePlatform(listOf(resource("a"), resource("b")))

        UploadCycle(engineOver(backend), platform).run()

        assertTrue(platform.drained, "queue should be drained first")
        assertEquals(listOf("a", "b"), platform.created.map { it.filename })
        assertEquals(LedgerState.REQUESTED, backend.get("a")?.state)
        assertEquals(LedgerState.REQUESTED, backend.get("b")?.state)
    }

    @Test
    fun skips_a_resource_already_proven_uploaded() = runTest {
        val backend = InMemoryLedgerBackend()
        // A COMPLETED proof for the same version is the only thing that makes the engine skip.
        LedgerWriter(backend).recordCompleted("a", attempt = 0, version = "v1")
        val platform = FakePlatform(listOf(resource("a", version = "v1")))

        UploadCycle(engineOver(backend), platform).run()

        assertTrue(platform.created.isEmpty(), "AlreadyUploaded must create no job")
    }

    @Test
    fun re_uploads_when_the_version_changed() = runTest {
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordCompleted("a", attempt = 0, version = "v1")
        val platform = FakePlatform(listOf(resource("a", version = "v2")))

        UploadCycle(engineOver(backend), platform).run()

        assertEquals(listOf("a"), platform.created.map { it.filename })
    }

    @Test
    fun empty_discovery_creates_nothing_but_still_drains() = runTest {
        val backend = InMemoryLedgerBackend()
        val platform = FakePlatform(emptyList())

        UploadCycle(engineOver(backend), platform).run()

        assertTrue(platform.drained)
        assertTrue(platform.created.isEmpty())
    }
}
