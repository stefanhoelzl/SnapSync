package app.snapsync.upload

import app.snapsync.model.AssetId
import app.snapsync.model.toLedgerRow
import app.snapsync.fake.InMemoryLedgerStore

import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.destinationPathOf
import app.snapsync.feature.upload.LedgerWriter
import app.snapsync.model.Resource
import app.snapsync.model.SyncDecision
import app.snapsync.feature.upload.SyncEngine
import app.snapsync.model.SyncEvent
import app.snapsync.model.UploadError
import app.snapsync.model.UploadRequest

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class SyncEngineTest {

    private val provider = RecordingUploadRequestProvider()
    private val store = InMemoryLedgerStore()
    private val ledger = LedgerWriter(store)

    private val engine = SyncEngine(provider, ledger)

    private fun resource(
        filename: String = "cloud-1-ios.photo.heic",
        assetId: String = "cloud-1",
    ) = Resource(
        filename = filename,
        assetId = AssetId(assetId),
        contentType = "image/heic",
        metadata = mapOf("asset-id" to "cloud-1", "created" to "2026-06-12T10:00:00Z"),
        data = byteArrayOf(1, 2, 3),
    )

    /**
     * Mint a request for [resource], then settle it as uploaded, returning the request. The engine records no
     * completion — the platform does, through the ledger's guarded terminal write — so the settled row is
     * stated directly.
     */
    private suspend fun completeUpload(resource: Resource): UploadRequest {
        val work = assertIs<SyncDecision.Work>(engine.handle(SyncEvent.ResourceChanged(resource)))
        store.recordUnlessSettled(resource.toLedgerRow(LedgerState.COMPLETED))
        return work.request
    }

    @Test
    fun `unknown resource uploads and writes nothing until started`() = runTest {
        val resource = resource()

        val decision = engine.handle(SyncEvent.ResourceChanged(resource))

        val upload = assertIs<SyncDecision.Upload>(decision)
        assertNull(ledger.entry(resource.filename)) // decide() is a pure query — no write

        engine.handle(SyncEvent.UploadStarted(upload.request))
        // REQUESTED is the one write that carries the destination: it is the moment an upload for this
        // row exists at the platform, so it is when the address becomes true (capability `photo-sharing`).
        assertEquals(
            resource.toLedgerRow(
                LedgerState.REQUESTED,
                destinationPath = destinationPathOf(upload.request.url),
            ),
            ledger.entry(resource.filename),
        )
    }

    @Test
    fun `provider is invoked exactly once with the platform's resource instance`() = runTest {
        val resource = resource()

        val decision = engine.handle(SyncEvent.ResourceChanged(resource))

        assertEquals(1, provider.invocations.size)
        assertSame(resource, provider.invocations.single())
        assertSame(provider.returned.single(), assertIs<SyncDecision.Work>(decision).request)
    }

    @Test
    fun `resource instance round-trips onto the decision's request`() = runTest {
        val resource = resource()

        val decision = engine.handle(SyncEvent.ResourceChanged(resource))

        assertSame(resource, assertIs<SyncDecision.Work>(decision).request.resource)
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
    fun `completed key skips even when re-submitted — an uploaded resource is immutable`() = runTest {
        val resource = resource()
        completeUpload(resource)
        val before = ledger.entry(resource.filename)

        // Re-submitting the same key never re-uploads; there is no content version to differ.
        val decision = engine.handle(SyncEvent.ResourceChanged(resource()))

        assertIs<SyncDecision.AlreadyUploaded>(decision)
        assertEquals(before, ledger.entry(resource.filename))
    }

    @Test
    fun `in-flight request skips re-submission`() = runTest {
        val work = assertIs<SyncDecision.Work>(engine.handle(SyncEvent.ResourceChanged(resource())))
        engine.handle(SyncEvent.UploadStarted(work.request)) // ledger now REQUESTED
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
        engine.handle(SyncEvent.UploadFailed(work.request, UploadError.Network)) // ledger back to DISCOVERED
        assertEquals(LedgerState.DISCOVERED, ledger.entry(resource().filename)?.state)

        val decision = engine.handle(SyncEvent.ResourceChanged(resource()))

        assertIs<SyncDecision.Upload>(decision)
    }

    @Test
    fun `failed upload yields a retry with a fresh request`() = runTest {
        val resource = resource()
        val failed = assertIs<SyncDecision.Work>(engine.handle(SyncEvent.ResourceChanged(resource))).request

        val decision = engine.handle(SyncEvent.UploadFailed(failed, UploadError.Http(403)))

        val retry = assertIs<SyncDecision.Retry>(decision)
        assertNotSame(failed, retry.request)
        assertSame(resource, retry.request.resource)
        // The retry is minted through the uncached credential read; the first request was not.
        assertEquals(listOf(resource), provider.retryInvocations)
        // UploadFailed returns the row to DISCOVERED only; the retry's REQUESTED comes via UploadStarted.
        assertEquals(
            resource.toLedgerRow(LedgerState.DISCOVERED),
            ledger.entry(resource.filename),
        )

        engine.handle(SyncEvent.UploadStarted(retry.request))
        assertEquals(
            resource.toLedgerRow(
                LedgerState.REQUESTED,
                destinationPath = destinationPathOf(retry.request.url),
            ),
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
        var request = assertIs<SyncDecision.Work>(engine.handle(SyncEvent.ResourceChanged(resource()))).request

        for (error in errors) {
            val retry = assertIs<SyncDecision.Retry>(engine.handle(SyncEvent.UploadFailed(request, error)))
            assertNotSame(request, retry.request, "every retry carries a freshly minted request")
            request = retry.request
        }
    }

    @Test
    fun `a late failure over a completed key still retries but never un-completes it`() = runTest {
        val resource = resource()
        val request = completeUpload(resource)
        val before = ledger.entry(resource.filename)

        val decision = engine.handle(SyncEvent.UploadFailed(request, UploadError.Network))

        // The engine's answer does not depend on the ledger's guard; the record is simply declined.
        assertIs<SyncDecision.Retry>(decision)
        assertEquals(before, ledger.entry(resource.filename))
        assertEquals(LedgerState.COMPLETED, ledger.entry(resource.filename)?.state)
    }

    @Test
    fun `a late start over a completed key never un-completes it`() = runTest {
        val resource = resource()
        val request = completeUpload(resource)
        val before = ledger.entry(resource.filename)

        val decision = engine.handle(SyncEvent.UploadStarted(request))

        assertIs<SyncDecision.AlreadyUploaded>(decision)
        assertEquals(before, ledger.entry(resource.filename))
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
    fun `replaying any suffix of an event history converges to the same ledger state`() = runTest {
        val resource = resource()
        val job0 = assertIs<SyncDecision.Upload>(engine.handle(SyncEvent.ResourceChanged(resource))).request
        engine.handle(SyncEvent.UploadStarted(job0))
        val job1 = assertIs<SyncDecision.Retry>(
            engine.handle(SyncEvent.UploadFailed(job0, UploadError.Network)),
        ).request
        engine.handle(SyncEvent.UploadStarted(job1))
        val history = listOf<SyncEvent>(
            SyncEvent.ResourceChanged(resource),
            SyncEvent.UploadStarted(job0),
            SyncEvent.UploadFailed(job0, UploadError.Network),
            SyncEvent.UploadStarted(job1),
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
        val request = assertIs<SyncDecision.Work>(engine.handle(SyncEvent.ResourceChanged(resource))).request

        engine.handle(SyncEvent.UploadStarted(request))

        assertEquals(AssetId("A"), ledger.entry(resource.filename)?.assetId)
    }

    @Test
    fun `assetId does not change the decision`() = runTest {
        // Absent ledger entry → Upload, regardless of assetId (decide reads only filename).
        val decision = engine.handle(SyncEvent.ResourceChanged(resource(assetId = "anything")))
        assertIs<SyncDecision.Upload>(decision)
    }

    // ---- isWork: the decision without the request ----

    @Test
    fun `isWork answers what handle would for every ledger state without minting or writing`() = runTest {
        val states: List<LedgerState?> = LedgerState.entries + null
        for (state in states) {
            val resource = resource(filename = "key-${state ?: "absent"}.heic", assetId = "a-${state ?: "absent"}")
            if (state != null) store.recordUnlessSettled(resource.toLedgerRow(state))
            val before = ledger.entry(resource.filename)
            val mintedBefore = provider.invocations.size

            val work = engine.isWork(resource)

            assertEquals(mintedBefore, provider.invocations.size, "isWork must not mint ($state)")
            assertEquals(before, ledger.entry(resource.filename), "isWork must not write ($state)")
            assertEquals(
                engine.handle(SyncEvent.ResourceChanged(resource)) is SyncDecision.Work,
                work,
                "isWork and handle must classify $state identically — the engine is the one place that decides",
            )
        }
    }

    @Test
    fun `isWork is true for an unknown or discovered key and false for a requested or completed one`() = runTest {
        assertTrue(engine.isWork(resource(filename = "new.heic")))

        val discovered = resource(filename = "discovered.heic")
        store.recordUnlessSettled(discovered.toLedgerRow(LedgerState.DISCOVERED))
        assertTrue(engine.isWork(discovered))

        val requested = resource(filename = "requested.heic")
        store.recordUnlessSettled(requested.toLedgerRow(LedgerState.REQUESTED))
        assertFalse(engine.isWork(requested))

        val completed = resource(filename = "completed.heic")
        store.recordUnlessSettled(completed.toLedgerRow(LedgerState.COMPLETED))
        assertFalse(engine.isWork(completed))

        assertEquals(0, provider.invocations.size, "no request is minted to answer the question")
    }

    @Test
    fun `isWork is untouched by a provider that would fail`() = runTest {
        provider.nextFailure = IllegalStateException("provider down")

        assertTrue(engine.isWork(resource()), "nothing is minted, so nothing can fail")
    }
}
