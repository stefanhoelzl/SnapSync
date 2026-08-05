package app.snapsync.download

import app.snapsync.feature.download.DownloadController
import app.snapsync.ports.EventUnionSource
import app.snapsync.ports.ImportResult
import app.snapsync.ports.PhotoDownloadJobs
import app.snapsync.ports.PhotoLibraryImporter
import app.snapsync.ports.UnionAsset
import app.snapsync.ports.UnionResource

import app.snapsync.ports.AssetRef
import app.snapsync.fake.InMemoryDownloadStore
import app.snapsync.ports.PendingDownload
import app.snapsync.ports.StagedResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import app.snapsync.ports.OsReceipt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class DownloadControllerTest {

    private val myDevice = "DEVICE-ME"

    private fun asset(device: String, id: String) = UnionAsset(
        deviceId = device,
        assetId = id,
        creationDate = "2026-06-30T10:00:00Z",
        resources = listOf(
            UnionResource("$id-primary.heic", "https://e/$id/primary", "primary", "image/heic", "IMG.HEIC"),
            UnionResource("$id-live.mov", "https://e/$id/live", "live", "video/quicktime", "IMG.MOV"),
        ),
    )

    private class FakeUnion(private val assets: List<UnionAsset>, val ok: Boolean = true) : EventUnionSource {
        var calls = 0
        override suspend fun union(eventId: String): Result<List<UnionAsset>> {
            calls++
            return if (ok) Result.success(assets) else Result.failure(RuntimeException("boom"))
        }
    }

    private class RecordingJobs : PhotoDownloadJobs {
        val enqueued = mutableListOf<PendingDownload>()
        var cancelled = false
        override suspend fun enqueue(downloads: List<PendingDownload>) { enqueued += downloads }
        override suspend fun cancelAll() { cancelled = true }
    }

    /** Imports successfully, minting a deterministic created local id per asset; records nothing else. */
    private class FakeImporter : PhotoLibraryImporter {
        val imported = mutableListOf<AssetRef>()
        val attempted = mutableListOf<AssetRef>()
        var failNext = false

        /** Assets whose import reports [ImportResult.TimedOut] — the device-unhealthy answer. */
        val timeOutFor = mutableSetOf<String>()

        override suspend fun import(ref: AssetRef, resources: List<StagedResource>, creationDate: String): ImportResult {
            attempted += ref
            if (ref.sourceAssetId in timeOutFor) return ImportResult.TimedOut("forced timeout")
            if (failNext) return ImportResult.Failed("forced")
            imported += ref
            return ImportResult.Imported("LOCAL-${ref.sourceAssetId}_L0_001")
        }
    }

    private fun controller(
        union: EventUnionSource,
        store: InMemoryDownloadStore = InMemoryDownloadStore(),
        jobs: RecordingJobs = RecordingJobs(),
        importer: FakeImporter = FakeImporter(),
        downloadEnabled: () -> Boolean? = { true },
    ) = DownloadController(union, store, jobs, importer, myDevice, downloadEnabled)

    @Test
    fun reconcile_skips_own_device_and_plans_only_foreign() = runTest {
        val jobs = RecordingJobs()
        val union = FakeUnion(listOf(asset(myDevice, "MINE"), asset("DEVICE-A", "FOREIGN")))
        controller(union, jobs = jobs).reconcile("event")

        // Only FOREIGN's two resources are enqueued; MINE (own) is skipped.
        assertEquals(setOf("FOREIGN-primary.heic", "FOREIGN-live.mov"), jobs.enqueued.map { it.resource.resourceKey }.toSet())
        assertTrue(jobs.enqueued.all { it.ref.sourceDeviceId == "DEVICE-A" })
    }

    @Test
    fun staged_resources_trigger_import_and_record_suppression() = runTest {
        val store = InMemoryDownloadStore()
        val importer = FakeImporter()
        val c = controller(FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, importer = importer)
        val ref = AssetRef("DEVICE-A", "Q")

        c.reconcile("event")
        assertFalse(store.isImported(ref)) // nothing staged yet
        c.onResourceStaged(ref, "Q-primary.heic", "/stage/p")
        assertTrue(importer.imported.isEmpty()) // live still missing → not importable
        c.onResourceStaged(ref, "Q-live.mov", "/stage/l")

        assertEquals(listOf(ref), importer.imported)
        assertTrue(store.isImported(ref))
        assertEquals(setOf("LOCAL-Q_L0_001"), store.suppressedLocalIds()) // suppression handle recorded
        assertEquals(1, store.importedCount())
    }

    @Test
    fun a_failed_import_stays_importable_for_retry() = runTest {
        val store = InMemoryDownloadStore()
        val importer = FakeImporter().also { it.failNext = true }
        val c = controller(FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, importer = importer)
        val ref = AssetRef("DEVICE-A", "Q")

        c.reconcile("event")
        c.onResourceStaged(ref, "Q-primary.heic", "/p")
        c.onResourceStaged(ref, "Q-live.mov", "/l")
        assertFalse(store.isImported(ref)) // failed → not imported

        importer.failNext = false
        c.importReady() // retry succeeds
        assertTrue(store.isImported(ref))
    }

    @Test
    fun reconcile_is_a_noop_when_there_is_no_membership_at_all() = runTest {
        // `null` = no membership, a DISTINCT answer from "a membership that excludes download" — and
        // neither enables the arm. The gate used to be two-valued, bound at the root with a `?: true`, so
        // this case resolved to "download freely": the same collapse `UploadArm`'s KDoc blames for starting
        // an upload producer for an event that did not exist. It was unreachable only because every caller
        // happened to pass a config-derived event id — a property of the callers, not of the gate.
        val store = InMemoryDownloadStore()
        val jobs = RecordingJobs()
        val union = FakeUnion(listOf(asset("DEVICE-A", "FOREIGN")))
        controller(union, store = store, jobs = jobs, downloadEnabled = { null }).reconcile("event")

        assertEquals(0, union.calls, "no union fetch without a membership to reconcile against")
        assertTrue(jobs.enqueued.isEmpty(), "no downloads enqueued")
        assertEquals(0, store.importedCount())
    }

    @Test
    fun reconcile_is_a_noop_when_download_is_disabled() = runTest {
        // Upload-only membership: reconcile must not even fetch the union, let alone enqueue or import.
        val store = InMemoryDownloadStore()
        val jobs = RecordingJobs()
        val union = FakeUnion(listOf(asset("DEVICE-A", "FOREIGN")))
        controller(union, store = store, jobs = jobs, downloadEnabled = { false }).reconcile("event")

        assertEquals(0, union.calls, "no union fetch when download is disabled")
        assertTrue(jobs.enqueued.isEmpty(), "no downloads enqueued when download is disabled")
        assertEquals(0, store.importedCount())
    }

    @Test
    fun reconcile_runs_normally_when_download_is_enabled() = runTest {
        // Both / download-only membership: reconcile behaves exactly as before.
        val jobs = RecordingJobs()
        val union = FakeUnion(listOf(asset("DEVICE-A", "FOREIGN")))
        controller(union, jobs = jobs, downloadEnabled = { true }).reconcile("event")

        assertEquals(1, union.calls)
        assertEquals(
            setOf("FOREIGN-primary.heic", "FOREIGN-live.mov"),
            jobs.enqueued.map { it.resource.resourceKey }.toSet(),
        )
    }

    @Test
    fun union_failure_keeps_last_state() = runTest {
        val store = InMemoryDownloadStore()
        val jobs = RecordingJobs()
        controller(FakeUnion(emptyList(), ok = false), store = store, jobs = jobs).reconcile("event")
        assertTrue(jobs.enqueued.isEmpty())
        assertEquals(0, store.importedCount())
    }

    /**
     * A failed union fetch costs the wake its DISCOVERY, not its WORK: the drain reads only the store
     * and bytes already on disk. This was inert while a failing fetch consumed the whole wake; with an
     * explicit request timeout it returns in seconds, so skipping the drain would strand importable
     * assets for no reason.
     */
    @Test
    fun union_failure_still_drains_staged_imports() = runTest {
        val store = InMemoryDownloadStore()
        val jobs = RecordingJobs()
        val importer = FakeImporter()
        val union = FakeUnion(listOf(asset("DEVICE-A", "Q")))
        val ref = AssetRef("DEVICE-A", "Q")

        // A good wake plans the asset and stages one of its two resources — not yet importable.
        val c = controller(union, store = store, jobs = jobs, importer = importer)
        c.reconcile("event")
        c.onResourceStaged(ref, "Q-primary.heic", "/stage/p")
        assertTrue(importer.imported.isEmpty())

        // The second resource lands, then the NEXT wake's union fetch times out. The asset is fully
        // staged, so this wake must still import it.
        store.markStaged(ref, "Q-live.mov", "/stage/l")
        val enqueuedBefore = jobs.enqueued.size
        controller(FakeUnion(emptyList(), ok = false), store = store, jobs = jobs, importer = importer)
            .reconcile("event")

        assertEquals(listOf(ref), importer.imported, "a fast union failure must not strand a staged asset")
        assertTrue(store.isImported(ref))
        assertEquals(enqueuedBefore, jobs.enqueued.size, "discovery is skipped: nothing new is enqueued")
    }

    /**
     * A timeout says the DEVICE is not answering, not that this photo is bad — so the wake's drain
     * stops rather than starting an import per remaining asset against a stalled library, each of which
     * may still commit and become a duplicate. The stopped assets stay importable for the next wake.
     */
    @Test
    fun an_import_timeout_stops_this_wakes_drain_and_leaves_the_rest_importable() = runTest {
        val store = InMemoryDownloadStore()
        val importer = FakeImporter()
        val union = FakeUnion(listOf(asset("DEVICE-A", "AAA"), asset("DEVICE-A", "BBB")))
        val c = controller(union, store = store, importer = importer)
        c.reconcile("event")

        // Both assets fully staged, so both are importable in one drain; the FIRST one times out.
        for (id in listOf("AAA", "BBB")) {
            val ref = AssetRef("DEVICE-A", id)
            store.markStaged(ref, "$id-primary.heic", "/stage/$id/p")
            store.markStaged(ref, "$id-live.mov", "/stage/$id/l")
        }
        importer.timeOutFor += store.importableAssets().first().ref.sourceAssetId

        c.importReady()

        assertEquals(1, importer.attempted.size, "the drain stopped at the timeout instead of continuing")
        assertEquals(0, store.importedCount(), "nothing was imported in the stalled wake")
        assertEquals(2, store.importableAssets().size, "both assets stay importable for the next wake")

        // The next wake, with the library healthy again, drains both.
        importer.timeOutFor.clear()
        c.importReady()
        assertEquals(2, store.importedCount())
    }

    /**
     * The two bounds together, over real parts (capability `ios-app-shell` + `photo-download`): a wake
     * whose import never answers must still release its OS handler, and must leave the photo importable.
     * This is the SNAPSYNC-6 shape — an import suspended in `performChanges` holding the controller's
     * mutex when the process died — with both bounds in place.
     *
     * The shell wiring that supplies the real handler is `:app:ios`, untested by rule; it is verified on
     * device instead. What is testable here is that the pieces compose to the right outcome.
     */
    @Test
    fun a_hung_import_still_releases_the_receipt_and_leaves_the_asset_importable() = runTest {
        val store = InMemoryDownloadStore()
        val hang = CompletableDeferred<Unit>()
        var released = false
        val ref = AssetRef("DEVICE-A", "Q")

        val hangingImporter = object : PhotoLibraryImporter {
            override suspend fun import(r: AssetRef, res: List<StagedResource>, creationDate: String): ImportResult {
                // Exactly the production shape: bound the WAIT, never the library call.
                return withTimeoutOrNull(5.seconds) { hang.await(); ImportResult.Imported("never") }
                    ?: ImportResult.TimedOut("no completion within 5s")
            }
        }
        val c = DownloadController(
            FakeUnion(listOf(asset("DEVICE-A", "Q"))), store, RecordingJobs(),
            hangingImporter, myDevice, { true },
        )
        c.reconcile("event")
        store.markStaged(ref, "Q-primary.heic", "/p")
        store.markStaged(ref, "Q-live.mov", "/l")

        val receipt = OsReceipt("test-wake", 20.seconds, release = { released = true })
        receipt.heldFor { c.importReady() }

        assertTrue(released, "the OS handler must be released even though the import never answered")
        assertFalse(store.isImported(ref), "a photo whose import was abandoned stays importable")
        assertEquals(1, store.importableAssets().size)

        // And the controller's lock was freed, so the next wake can drain at all.
        c.onLeaveOrSwitch()
    }

    @Test
    fun leave_cancels_and_prunes_non_terminal() = runTest {
        val store = InMemoryDownloadStore()
        val jobs = RecordingJobs()
        val c = controller(FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, jobs = jobs)
        c.reconcile("event")
        c.onLeaveOrSwitch()
        assertTrue(jobs.cancelled)
        assertTrue(store.pendingDownloads().isEmpty()) // non-terminal dropped
    }
}
