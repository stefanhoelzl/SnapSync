package app.snapsync.membership

import app.snapsync.ports.DeviceFilesSource
import app.snapsync.ports.JoinedEventMarker

import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.LedgerWriter
import app.snapsync.model.Resource
import app.snapsync.model.SyncDecision
import app.snapsync.model.SyncEngine
import app.snapsync.model.SyncEvent
import app.snapsync.model.UploadRequest
import app.snapsync.model.UploadRequestProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
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
        ledger: FakeLedgerStore,
        marker: JoinedEventMarker,
        onCursorClear: () -> Unit = {},
    ) = ExtensionReconciler(files, ledger, marker, deviceId, { onCursorClear() })

    @Test
    fun `marker mismatch reset-seeds every stored file then clears the cursor and sets the marker`() = runTest {
        val files = FakeFiles(Result.success(listOf("A-primary.heic", "A-live.mov")))
        val ledger = FakeLedgerStore()
        val marker = FakeMarker(null)
        var cursorCleared = 0

        assertTrue(reconciler(files, ledger, marker) { cursorCleared++ }.reconcile("E1"))

        assertEquals(1, files.calls)
        assertEquals(deviceId, files.lastDeviceId) // listed by DEVICE, not event
        val primary = ledger.get("A-primary.heic")!!
        assertEquals(LedgerState.COMPLETED, primary.state)
        assertEquals("A", primary.assetId) // assetId recovered from the filename
        assertEquals(LedgerState.COMPLETED, ledger.get("A-live.mov")!!.state)
        assertEquals(1, cursorCleared) // re-join forces a full re-enumeration
        assertEquals("E1", marker.read())
    }

    @Test
    fun `assetId is recovered from a uuid-style key whose assetId contains hyphens`() = runTest {
        val files = FakeFiles(Result.success(listOf("E1B2C3D4-1234-5678_L0_001-primary.jpg")))
        val ledger = FakeLedgerStore()
        reconciler(files, ledger, FakeMarker(null)).reconcile("E1")
        assertEquals(
            "E1B2C3D4-1234-5678_L0_001",
            ledger.get("E1B2C3D4-1234-5678_L0_001-primary.jpg")!!.assetId,
        )
    }

    @Test
    fun `marker match skips the join and uploads directly without fetching or clearing the cursor`() = runTest {
        val files = FakeFiles(Result.success(emptyList()))
        val ledger = FakeLedgerStore()
        var cursorCleared = 0

        assertTrue(reconciler(files, ledger, FakeMarker("E1")) { cursorCleared++ }.reconcile("E1"))

        assertEquals(0, files.calls) // no fetch, no seed
        assertEquals(0, cursorCleared) // an already-joined event re-enumerates incrementally
        assertTrue(ledger.rows.isEmpty())
    }

    @Test
    fun `no event configured and no marker does nothing`() = runTest {
        val files = FakeFiles(Result.success(emptyList()))
        val ledger = FakeLedgerStore()

        assertFalse(reconciler(files, ledger, FakeMarker(null)).reconcile(null))

        assertEquals(0, files.calls)
        assertTrue(ledger.rows.isEmpty())
    }

    @Test
    fun `a zero-file join still sets the marker so the next cycle does not re-loop`() = runTest {
        val files = FakeFiles(Result.success(emptyList())) // nothing stored for this device yet
        val ledger = FakeLedgerStore()
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
        val ledger = FakeLedgerStore()
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
    fun `a listing timeout defers without settling`() = runTest {
        // A hung LIST must not stall the OS-scheduled cycle: withTimeoutOrNull fires (virtual time) and
        // the reconcile defers exactly like a failed fetch — no seed, marker unset, ledger untouched.
        val hanging = object : DeviceFilesSource {
            override suspend fun list(deviceId: String): Result<List<String>> {
                delay(Long.MAX_VALUE) // never returns
                return Result.success(emptyList())
            }
        }
        val ledger = FakeLedgerStore()
        val marker = FakeMarker(null)

        assertFalse(reconciler(hanging, ledger, marker).reconcile("E1"))
        assertNull(marker.read())
        assertTrue(ledger.rows.isEmpty())
    }

    @Test
    fun `a storage reset - empty listing against a non-empty ledger - re-baselines and re-uploads`() = runTest {
        // A confirmed-successful empty listing is AUTHORITATIVE: the objects were deleted from storage
        // (a reset), not transiently un-listed. So the ledger must be wiped to empty and the cursor
        // cleared, so the producer re-uploads everything — NOT deferred (which hung the device forever).
        val ledger = FakeLedgerStore().apply {
            put(LedgerEntry("stored-primary.heic", "stored", LedgerState.COMPLETED, 0))
        }
        val files = FakeFiles(Result.success(emptyList()))
        val marker = FakeMarker("OLD")
        var cursorCleared = 0

        assertTrue(reconciler(files, ledger, marker) { cursorCleared++ }.reconcile("NEW"))
        assertTrue(ledger.rows.isEmpty()) // re-baselined to exactly what storage holds (nothing)
        assertEquals(1, cursorCleared) // cursor cleared → full re-enumeration re-uploads everything
        assertEquals("NEW", marker.read()) // settled, not looping
    }

    @Test
    fun `a partial storage deletion re-uploads only the missing files`() = runTest {
        // Listing reports a strict SUBSET of the ledger's prior COMPLETED files (some objects deleted
        // from storage). resetTo seeds only the still-stored files; the deleted one drops out and re-uploads.
        val ledger = FakeLedgerStore().apply {
            put(LedgerEntry("kept-primary.heic", "kept", LedgerState.COMPLETED, 0))
            put(LedgerEntry("gone-primary.heic", "gone", LedgerState.COMPLETED, 0)) // deleted from storage
        }
        val files = FakeFiles(Result.success(listOf("kept-primary.heic"))) // only the survivor is listed
        val marker = FakeMarker("OLD")
        var cursorCleared = 0

        assertTrue(reconciler(files, ledger, marker) { cursorCleared++ }.reconcile("NEW"))
        assertEquals(LedgerState.COMPLETED, ledger.get("kept-primary.heic")!!.state) // still deduped
        assertNull(ledger.get("gone-primary.heic")) // dropped → producer re-uploads it
        assertEquals(1, cursorCleared)
        assertEquals("NEW", marker.read())
    }

    @Test
    fun `an empty listing on a fresh device with no COMPLETED rows still settles`() = runTest {
        // A genuinely fresh/empty device (empty listing, no COMPLETED rows): settles with zero seeded rows.
        val ledger = FakeLedgerStore()
        val marker = FakeMarker(null)

        assertTrue(reconciler(FakeFiles(Result.success(emptyList())), ledger, marker).reconcile("E1"))
        assertTrue(ledger.rows.isEmpty())
        assertEquals("E1", marker.read()) // settled with zero seeded rows
    }

    @Test
    fun `re-join reset-seeds from the device listing keeping stored files and clearing phantom rows`() = runTest {
        // Dedup: a file still in the device listing stays COMPLETED (no re-upload). But resetTo also
        // CLEARS a phantom row whose job never materialized — otherwise the engine skips it forever.
        val ledger = FakeLedgerStore().apply {
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
        val ledger = FakeLedgerStore().apply { put(LedgerEntry("k-primary.heic", "k", LedgerState.COMPLETED, 0)) }
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
            UploadRequest("https://edge.example/files/devices/dev/${resource.filename}", emptyMap(), resource)
    }

    private fun res(filename: String, assetId: String) =
        Resource(filename, assetId, "image/heic", emptyMap(), Unit)

    @Test
    fun `a seeded resource is skipped by the engine and an unlisted one uploads`() = runTest {
        val ledger = FakeLedgerStore()
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
