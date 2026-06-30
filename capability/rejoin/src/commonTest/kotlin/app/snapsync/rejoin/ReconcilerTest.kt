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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ReconcilerTest {

    private val deviceId = "11111111-1111-4111-8111-111111111111"

    private class FakeFiles(var result: Result<List<String>>) : DeviceFilesSource {
        var calls = 0
        var lastDeviceId: String? = null
        override suspend fun list(deviceId: String): Result<List<String>> {
            calls++
            lastDeviceId = deviceId
            return result
        }
    }

    private class FakeMarker(private var value: String? = null) : JoinedEventMarker {
        override fun read(): String? = value
        override fun set(eventId: String) { value = eventId }
        override fun clear() { value = null }
    }

    private fun reconciler(
        files: DeviceFilesSource,
        ledger: FakeLedgerBackend,
        marker: JoinedEventMarker,
        onCursorClear: () -> Unit = {},
    ) = ExtensionReconciler(files, ledger, marker, deviceId, { onCursorClear() })

    @Test
    fun `marker mismatch reset-seeds every stored file then clears the cursor and sets the marker`() = runTest {
        val files = FakeFiles(Result.success(listOf("A-primary.heic", "A-motion.mov")))
        val ledger = FakeLedgerBackend()
        val marker = FakeMarker(null)
        var cursorCleared = 0

        assertTrue(reconciler(files, ledger, marker) { cursorCleared++ }.reconcile("E1"))

        assertEquals(1, files.calls)
        assertEquals(deviceId, files.lastDeviceId) // listed by DEVICE, not event
        val primary = ledger.get("A-primary.heic")!!
        assertEquals(LedgerState.COMPLETED, primary.state)
        assertEquals("A", primary.assetId) // assetId recovered from the filename
        assertEquals(LedgerState.COMPLETED, ledger.get("A-motion.mov")!!.state)
        assertEquals(1, cursorCleared) // re-join forces a full re-enumeration
        assertEquals("E1", marker.read())
    }

    @Test
    fun `assetId is recovered from a uuid-style key whose assetId contains hyphens`() = runTest {
        val files = FakeFiles(Result.success(listOf("E1B2C3D4-1234-5678_L0_001-primary.jpg")))
        val ledger = FakeLedgerBackend()
        reconciler(files, ledger, FakeMarker(null)).reconcile("E1")
        assertEquals(
            "E1B2C3D4-1234-5678_L0_001",
            ledger.get("E1B2C3D4-1234-5678_L0_001-primary.jpg")!!.assetId,
        )
    }

    @Test
    fun `marker match skips the join and uploads directly without fetching or clearing the cursor`() = runTest {
        val files = FakeFiles(Result.success(emptyList()))
        val ledger = FakeLedgerBackend()
        var cursorCleared = 0

        assertTrue(reconciler(files, ledger, FakeMarker("E1")) { cursorCleared++ }.reconcile("E1"))

        assertEquals(0, files.calls) // no fetch, no seed
        assertEquals(0, cursorCleared) // an already-joined event re-enumerates incrementally
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
    fun `a zero-file join still sets the marker so the next cycle does not re-loop`() = runTest {
        val files = FakeFiles(Result.success(emptyList())) // nothing stored for this device yet
        val ledger = FakeLedgerBackend()
        val marker = FakeMarker(null)
        val r = reconciler(files, ledger, marker)

        assertTrue(r.reconcile("E1"))
        assertTrue(ledger.rows.isEmpty())
        assertEquals("E1", marker.read())

        assertTrue(r.reconcile("E1")) // marker now matches
        assertEquals(1, files.calls) // no second fetch — no re-seed loop
    }

    @Test
    fun `a fetch failure defers uploads and leaves the marker unset to retry`() = runTest {
        val files = FakeFiles(Result.failure(RuntimeException("net")))
        val ledger = FakeLedgerBackend()
        val marker = FakeMarker(null)
        val r = reconciler(files, ledger, marker)

        assertFalse(r.reconcile("E1"))
        assertNull(marker.read())
        assertTrue(ledger.rows.isEmpty())

        files.result = Result.success(listOf("A-primary.heic"))
        assertTrue(r.reconcile("E1"))
        assertEquals(2, files.calls)
        assertEquals(LedgerState.COMPLETED, ledger.get("A-primary.heic")!!.state)
        assertEquals("E1", marker.read())
    }

    @Test
    fun `re-join reset-seeds from the device listing keeping stored files and clearing phantom rows`() = runTest {
        // Dedup: a file still in the device listing stays COMPLETED (no re-upload). But resetTo also
        // CLEARS a phantom row whose job never materialized — otherwise the engine skips it forever.
        val ledger = FakeLedgerBackend().apply {
            put(LedgerEntry("stored-primary.heic", "stored", LedgerState.COMPLETED, 0)) // really in /files
            put(LedgerEntry("phantom-primary.heic", "phantom", LedgerState.REQUESTED, 0)) // job never created
        }
        val files = FakeFiles(Result.success(listOf("stored-primary.heic", "new-primary.heic")))
        val marker = FakeMarker("OLD")

        assertTrue(reconciler(files, ledger, marker).reconcile("NEW"))

        assertNotNull(ledger.get("stored-primary.heic")) // in the listing → kept COMPLETED (dedup)
        assertEquals(LedgerState.COMPLETED, ledger.get("new-primary.heic")!!.state) // newly listed
        assertNull(ledger.get("phantom-primary.heic")) // NOT in the listing → cleared, so it re-uploads
        assertEquals("NEW", marker.read())
    }

    @Test
    fun `leaving clears the marker but keeps the ledger intact`() = runTest {
        val ledger = FakeLedgerBackend().apply { put(LedgerEntry("k-primary.heic", "k", LedgerState.COMPLETED, 0)) }
        val files = FakeFiles(Result.success(emptyList()))
        val marker = FakeMarker("E1")

        assertFalse(reconciler(files, ledger, marker).reconcile(null))

        assertNotNull(ledger.get("k-primary.heic")) // ledger kept (global; re-join dedups against it)
        assertNull(marker.read()) // marker forgotten
        assertEquals(0, files.calls)
    }

    // End-to-end across the reconcile → engine boundary: a seeded row reads back through the real
    // SyncEngine as AlreadyUploaded (no job), while a resource absent from the listing still uploads.
    private object FakeProvider : UploadRequestProvider {
        override suspend fun provide(resource: Resource): UploadRequest =
            UploadRequest("https://edge.example/files/device/dev/${resource.filename}", emptyMap(), resource)
    }

    private fun res(filename: String, assetId: String) =
        Resource(filename, assetId, "image/heic", emptyMap(), Unit)

    @Test
    fun `a seeded resource is skipped by the engine and an unlisted one uploads`() = runTest {
        val ledger = FakeLedgerBackend()
        reconciler(FakeFiles(Result.success(listOf("A-primary.heic"))), ledger, FakeMarker(null)).reconcile("E1")

        val engine = SyncEngine(FakeProvider, LedgerWriter(ledger))
        assertEquals(
            SyncDecision.AlreadyUploaded,
            engine.handle(SyncEvent.ResourceChanged(res("A-primary.heic", "A"))),
        )
        val decision = engine.handle(SyncEvent.ResourceChanged(res("B-primary.heic", "B")))
        assertTrue(decision is SyncDecision.Upload, "un-seeded resource must upload, got $decision")
    }
}
