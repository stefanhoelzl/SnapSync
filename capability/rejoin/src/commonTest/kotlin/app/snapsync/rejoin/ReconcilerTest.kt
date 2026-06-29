package app.snapsync.rejoin

import app.snapsync.engine.LedgerEntry
import app.snapsync.engine.LedgerState
import app.snapsync.engine.LedgerWriter
import app.snapsync.engine.Resource
import app.snapsync.engine.SyncDecision
import app.snapsync.engine.SyncEngine
import app.snapsync.engine.SyncEvent
import app.snapsync.engine.UploadRequest
import app.snapsync.engine.UploadRequestProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ReconcilerTest {

    private class FakeFiles(var result: Result<List<RemoteAsset>>) : EventFilesSource {
        var calls = 0
        override suspend fun list(eventId: String): Result<List<RemoteAsset>> {
            calls++
            return result
        }
    }

    private class FakeMarker(private var value: String? = null) : JoinedEventMarker {
        override fun read(): String? = value
        override fun set(eventId: String) { value = eventId }
        override fun clear() { value = null }
    }

    private fun asset(assetId: String, vararg filenames: String) =
        RemoteAsset(assetId, filenames.map { RemoteResource(it) })

    private fun reconciler(
        files: EventFilesSource,
        ledger: FakeLedgerBackend,
        marker: JoinedEventMarker,
        onCursorClear: () -> Unit = {},
        onManifestsReset: () -> Unit = {},
    ) = ExtensionReconciler(files, ledger, marker, { onCursorClear() }, { onManifestsReset() })

    @Test
    fun `marker mismatch triggers a join that seeds every resource and sets the marker`() = runTest {
        val files = FakeFiles(Result.success(listOf(asset("A", "A-primary.heic", "A-motion.mov"))))
        val ledger = FakeLedgerBackend()
        val marker = FakeMarker(null)
        var cursorCleared = 0

        assertTrue(reconciler(files, ledger, marker) { cursorCleared++ }.reconcile("E1"))

        assertEquals(1, files.calls)
        val primary = ledger.get("A-primary.heic")!!
        assertEquals(LedgerState.COMPLETED, primary.state)
        assertEquals("A", primary.assetId) // carries the asset's id
        assertEquals(LedgerState.COMPLETED, ledger.get("A-motion.mov")!!.state)
        assertNull(ledger.get("B-primary.heic")) // an asset absent from the listing is not seeded
        assertEquals("E1", marker.read())
        assertEquals(1, cursorCleared)
    }

    @Test
    fun `marker match skips the join and uploads directly without fetching`() = runTest {
        val files = FakeFiles(Result.success(emptyList()))
        val ledger = FakeLedgerBackend()

        assertTrue(reconciler(files, ledger, FakeMarker("E1")).reconcile("E1"))

        assertEquals(0, files.calls) // no fetch, no enumeration, no seed
        assertTrue(ledger.rows.isEmpty())
    }

    @Test
    fun `no event configured and no marker does nothing`() = runTest {
        val files = FakeFiles(Result.success(emptyList()))
        val ledger = FakeLedgerBackend()

        assertFalse(reconciler(files, ledger, FakeMarker(null)).reconcile(null))

        assertEquals(0, files.calls)
        assertTrue(ledger.rows.isEmpty())
    }

    @Test
    fun `a zero-row join still sets the marker so the next cycle does not re-loop`() = runTest {
        val files = FakeFiles(Result.success(emptyList())) // freshly provisioned event, nothing stored yet
        val ledger = FakeLedgerBackend()
        val marker = FakeMarker(null)
        val r = reconciler(files, ledger, marker)

        assertTrue(r.reconcile("E1"))
        assertTrue(ledger.rows.isEmpty()) // nothing seeded
        assertEquals("E1", marker.read()) // but settled

        assertTrue(r.reconcile("E1")) // now the marker matches
        assertEquals(1, files.calls) // no second fetch — no re-seed loop
    }

    @Test
    fun `a fetch failure defers uploads and leaves the marker unset to retry`() = runTest {
        val files = FakeFiles(Result.failure(RuntimeException("net")))
        val ledger = FakeLedgerBackend()
        val marker = FakeMarker(null)
        val r = reconciler(files, ledger, marker)

        assertFalse(r.reconcile("E1")) // defer: create no jobs this cycle
        assertNull(marker.read()) // not settled
        assertTrue(ledger.rows.isEmpty()) // ledger untouched on failure

        files.result = Result.success(listOf(asset("A", "A-primary.heic")))
        assertTrue(r.reconcile("E1")) // the next cycle retries the fetch
        assertEquals(2, files.calls)
        assertEquals(LedgerState.COMPLETED, ledger.get("A-primary.heic")!!.state)
        assertEquals("E1", marker.read())
    }

    @Test
    fun `a different event resets the ledger and reconciles for the new event`() = runTest {
        val ledger = FakeLedgerBackend().apply { put(LedgerEntry("old", "OLD", LedgerState.COMPLETED, 0)) }
        val files = FakeFiles(Result.success(listOf(asset("N", "N-primary.heic"))))
        val marker = FakeMarker("OLD")
        var cursorCleared = 0
        var manifestsReset = 0

        assertTrue(
            reconciler(files, ledger, marker, { cursorCleared++ }, { manifestsReset++ }).reconcile("NEW"),
        )

        assertNull(ledger.get("old")) // the prior event's rows are gone (atomic reset)
        assertEquals(LedgerState.COMPLETED, ledger.get("N-primary.heic")!!.state) // new event seeded
        assertEquals("NEW", marker.read())
        assertEquals(1, cursorCleared)
        assertEquals(1, manifestsReset) // switch re-uploads manifests (markers are assetId-keyed, not event)
    }

    @Test
    fun `a marker match resets neither the cursor nor the manifest markers`() = runTest {
        var cursorCleared = 0
        var manifestsReset = 0

        assertTrue(
            reconciler(FakeFiles(Result.success(emptyList())), FakeLedgerBackend(), FakeMarker("E1"),
                { cursorCleared++ }, { manifestsReset++ }).reconcile("E1"),
        )

        assertEquals(0, cursorCleared) // an already-joined event touches nothing
        assertEquals(0, manifestsReset)
    }

    @Test
    fun `leaving resets the ledger and clears the marker on the next cycle`() = runTest {
        val ledger = FakeLedgerBackend().apply { put(LedgerEntry("k", "k", LedgerState.COMPLETED, 0)) }
        val files = FakeFiles(Result.success(emptyList()))
        val marker = FakeMarker("E1")
        var cursorCleared = 0
        var manifestsReset = 0

        // Config absent (the user left) but a marker remains → reset and clear so a later provision is fresh.
        assertFalse(reconciler(files, ledger, marker, { cursorCleared++ }, { manifestsReset++ }).reconcile(null))

        assertTrue(ledger.rows.isEmpty())
        assertNull(marker.read())
        assertEquals(1, cursorCleared)
        assertEquals(1, manifestsReset)
        assertEquals(0, files.calls)
    }

    // End-to-end across the reconcile → engine boundary: a row seeded by the reconcile's atomic reset
    // reads back through the real SyncEngine as AlreadyUploaded (no job), while a resource absent from
    // the listing still uploads.
    private object FakeProvider : UploadRequestProvider {
        override suspend fun provide(resource: Resource): UploadRequest =
            UploadRequest("https://edge.example/file/${resource.filename}", emptyMap(), resource)
    }

    private fun res(filename: String, assetId: String) =
        Resource(filename, assetId, "image/heic", emptyMap(), Unit)

    @Test
    fun `a seeded resource is skipped by the engine and an unlisted one uploads`() = runTest {
        val ledger = FakeLedgerBackend()
        reconciler(FakeFiles(Result.success(listOf(asset("A", "A-primary.heic")))), ledger, FakeMarker(null))
            .reconcile("E1")

        val engine = SyncEngine(FakeProvider, LedgerWriter(ledger))
        assertEquals(
            SyncDecision.AlreadyUploaded,
            engine.handle(SyncEvent.ResourceChanged(res("A-primary.heic", "A"))),
        )
        val decision = engine.handle(SyncEvent.ResourceChanged(res("B-primary.heic", "B")))
        assertTrue(decision is SyncDecision.Upload, "un-seeded resource must upload, got $decision")
    }
}
