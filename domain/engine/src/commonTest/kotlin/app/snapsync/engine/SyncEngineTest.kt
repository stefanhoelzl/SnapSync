package app.snapsync.engine

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class SyncEngineTest {

    private val t0 = kotlin.time.Instant.fromEpochMilliseconds(1_000_000)
    private val provider = RecordingUploadRequestProvider()
    private val ledger = LedgerWriter(InMemoryLedgerBackend(), FixedClock(t0))
    private val engine = SyncEngine(provider, ledger)

    private fun resource(
        filename: String = "cloud-1-ios.photo.heic",
        version: String = "2026-06-12T10:00:00Z",
        assetId: String = "cloud-1",
    ) = Resource(
        filename = filename,
        assetId = assetId,
        contentType = "image/heic",
        version = version,
        metadata = mapOf("asset-id" to "cloud-1", "created" to "2026-06-12T10:00:00Z"),
        data = byteArrayOf(1, 2, 3),
    )

    private suspend fun completeUpload(resource: Resource): UploadJob {
        val work = assertIs<SyncDecision.Work>(engine.handle(SyncEvent.ResourceChanged(resource)))
        engine.handle(SyncEvent.UploadCompleted(work.job))
        return work.job
    }

    @Test
    fun `unknown resource uploads with first attempt and writes nothing until started`() = runTest {
        val resource = resource()

        val decision = engine.handle(SyncEvent.ResourceChanged(resource))

        val upload = assertIs<SyncDecision.Upload>(decision)
        assertEquals(0, upload.job.attempt)
        assertNull(ledger.entry(resource.filename)) // decide() is a pure query — no write

        engine.handle(SyncEvent.UploadStarted(upload.job))
        assertEquals(
            LedgerEntry(resource.filename, resource.assetId, LedgerState.REQUESTED, 0, resource.version, t0),
            ledger.entry(resource.filename),
        )
    }

    @Test
    fun `provider is invoked exactly once with the platform's resource instance`() = runTest {
        val resource = resource()

        val decision = engine.handle(SyncEvent.ResourceChanged(resource))

        assertEquals(1, provider.invocations.size)
        assertSame(resource, provider.invocations.single())
        assertSame(provider.returned.single(), assertIs<SyncDecision.Work>(decision).job.request)
    }

    @Test
    fun `resource instance round-trips onto the decision's job`() = runTest {
        val resource = resource()

        val decision = engine.handle(SyncEvent.ResourceChanged(resource))

        assertSame(resource, assertIs<SyncDecision.Work>(decision).job.request.resource)
    }

    @Test
    fun `completed and unchanged skips without minting or touching the ledger`() = runTest {
        val resource = resource()
        completeUpload(resource)
        val before = ledger.entry(resource.filename)
        val mintsBefore = provider.invocations.size

        val decision = engine.handle(SyncEvent.ResourceChanged(resource()))

        assertIs<SyncDecision.AlreadyUploaded>(decision)
        assertEquals(mintsBefore, provider.invocations.size)
        assertEquals(before, ledger.entry(resource.filename))
    }

    @Test
    fun `completed but changed re-uploads under the new version`() = runTest {
        completeUpload(resource(version = "v1"))
        val changed = resource(version = "v2")

        val decision = engine.handle(SyncEvent.ResourceChanged(changed))

        val reUpload = assertIs<SyncDecision.ReUpload>(decision)
        assertEquals(0, reUpload.job.attempt)
        // decide() writes nothing — the ledger still holds the completed v1 entry until UploadStarted
        assertEquals(LedgerState.COMPLETED, ledger.entry(changed.filename)?.state)

        engine.handle(SyncEvent.UploadStarted(reUpload.job))
        assertEquals(
            LedgerEntry(changed.filename, changed.assetId, LedgerState.REQUESTED, 0, "v2", t0),
            ledger.entry(changed.filename),
        )
    }

    @Test
    fun `version is compared for equality only`() = runTest {
        completeUpload(resource(version = "zebra"))

        val decision = engine.handle(SyncEvent.ResourceChanged(resource(version = "aardvark")))

        assertIs<SyncDecision.ReUpload>(decision)
    }

    @Test
    fun `in-flight request skips re-submission at the same version`() = runTest {
        val work = assertIs<SyncDecision.Work>(engine.handle(SyncEvent.ResourceChanged(resource())))
        engine.handle(SyncEvent.UploadStarted(work.job)) // ledger now REQUESTED
        val mintsBefore = provider.invocations.size

        val decision = engine.handle(SyncEvent.ResourceChanged(resource()))

        assertIs<SyncDecision.AlreadyUploaded>(decision)
        assertEquals(mintsBefore, provider.invocations.size)
    }

    @Test
    fun `dropped UploadStarted is re-issued not stranded`() = runTest {
        // ResourceChanged decided Work, but its UploadStarted was never delivered (platform died).
        assertIs<SyncDecision.Upload>(engine.handle(SyncEvent.ResourceChanged(resource())))
        assertNull(ledger.entry(resource().filename))

        val decision = engine.handle(SyncEvent.ResourceChanged(resource()))

        assertIs<SyncDecision.Upload>(decision) // re-issued, not skipped
    }

    @Test
    fun `failed entry re-uploads on resubmission`() = runTest {
        val work = assertIs<SyncDecision.Work>(engine.handle(SyncEvent.ResourceChanged(resource())))
        engine.handle(SyncEvent.UploadFailed(work.job, UploadError.Network)) // ledger now FAILED
        assertEquals(LedgerState.FAILED, ledger.entry(resource().filename)?.state)

        val decision = engine.handle(SyncEvent.ResourceChanged(resource()))

        assertIs<SyncDecision.Upload>(decision)
    }

    @Test
    fun `failed upload yields a retry with incremented attempt and a fresh request`() = runTest {
        val resource = resource()
        val failed = assertIs<SyncDecision.Work>(engine.handle(SyncEvent.ResourceChanged(resource))).job

        val decision = engine.handle(SyncEvent.UploadFailed(failed, UploadError.Http(403)))

        val retry = assertIs<SyncDecision.Retry>(decision)
        assertEquals(1, retry.job.attempt)
        assertNotSame(failed.request, retry.job.request)
        assertSame(resource, retry.job.request.resource)
        // UploadFailed records FAILED only; the retry's REQUESTED comes via UploadStarted.
        assertEquals(
            LedgerEntry(resource.filename, resource.assetId, LedgerState.FAILED, 0, resource.version, t0),
            ledger.entry(resource.filename),
        )

        engine.handle(SyncEvent.UploadStarted(retry.job))
        assertEquals(
            LedgerEntry(resource.filename, resource.assetId, LedgerState.REQUESTED, 1, resource.version, t0),
            ledger.entry(resource.filename),
        )
    }

    @Test
    fun `every error kind retries`() = runTest {
        val errors = listOf(
            UploadError.Network,
            UploadError.Http(500),
            UploadError.Cancelled,
            UploadError.Unknown("boom"),
        )
        var job = assertIs<SyncDecision.Work>(engine.handle(SyncEvent.ResourceChanged(resource()))).job

        for ((index, error) in errors.withIndex()) {
            val retry = assertIs<SyncDecision.Retry>(engine.handle(SyncEvent.UploadFailed(job, error)))
            assertEquals(index + 1, retry.job.attempt)
            job = retry.job
        }
    }

    @Test
    fun `provider failure propagates and leaves no trace in the ledger`() = runTest {
        val event = SyncEvent.ResourceChanged(resource())
        val failure = IllegalStateException("mint failed")
        provider.nextFailure = failure

        val thrown = assertFailsWith<IllegalStateException> { engine.handle(event) }

        assertSame(failure, thrown)
        assertNull(ledger.entry(resource().filename))
        assertIs<SyncDecision.Upload>(engine.handle(event))
    }

    @Test
    fun `completion marks the key done and answers already-uploaded`() = runTest {
        val resource = resource()
        val job = assertIs<SyncDecision.Work>(engine.handle(SyncEvent.ResourceChanged(resource))).job

        val decision = engine.handle(SyncEvent.UploadCompleted(job))

        assertIs<SyncDecision.AlreadyUploaded>(decision)
        assertEquals(
            LedgerEntry(resource.filename, resource.assetId, LedgerState.COMPLETED, 0, resource.version, t0),
            ledger.entry(resource.filename),
        )
    }

    @Test
    fun `duplicate completion is a no-op`() = runTest {
        val job = completeUpload(resource())
        val once = ledger.entry(resource().filename)

        val decision = engine.handle(SyncEvent.UploadCompleted(job))

        assertIs<SyncDecision.AlreadyUploaded>(decision)
        assertEquals(once, ledger.entry(resource().filename))
    }

    @Test
    fun `replaying any suffix of an event history converges to the same ledger state`() = runTest {
        val resource = resource()
        val job0 = assertIs<SyncDecision.Upload>(engine.handle(SyncEvent.ResourceChanged(resource))).job
        engine.handle(SyncEvent.UploadStarted(job0))
        val job1 = assertIs<SyncDecision.Retry>(
            engine.handle(SyncEvent.UploadFailed(job0, UploadError.Network)),
        ).job
        engine.handle(SyncEvent.UploadStarted(job1))
        engine.handle(SyncEvent.UploadCompleted(job1))
        val history = listOf<SyncEvent>(
            SyncEvent.ResourceChanged(resource),
            SyncEvent.UploadStarted(job0),
            SyncEvent.UploadFailed(job0, UploadError.Network),
            SyncEvent.UploadStarted(job1),
            SyncEvent.UploadCompleted(job1),
        )
        val settled = ledger.entry(resource.filename)

        for (start in history.indices) {
            for (event in history.subList(start, history.size)) {
                engine.handle(event)
            }
            assertEquals(settled, ledger.entry(resource.filename))
        }
    }

    @Test
    fun `assetId is carried into the recorded entry`() = runTest {
        val resource = resource(assetId = "A")
        val job = assertIs<SyncDecision.Work>(engine.handle(SyncEvent.ResourceChanged(resource))).job

        engine.handle(SyncEvent.UploadStarted(job))

        assertEquals("A", ledger.entry(resource.filename)?.assetId)
    }

    @Test
    fun `assetId does not change the decision`() = runTest {
        // Absent ledger entry → Upload, regardless of assetId (decide reads only filename + version).
        val decision = engine.handle(SyncEvent.ResourceChanged(resource(assetId = "anything")))
        assertIs<SyncDecision.Upload>(decision)
    }
}
