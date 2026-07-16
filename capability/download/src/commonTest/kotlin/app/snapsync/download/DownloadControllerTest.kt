package app.snapsync.download

import app.snapsync.downloadstore.AssetRef
import app.snapsync.downloadstore.InMemoryDownloadStore
import app.snapsync.downloadstore.PendingDownload
import app.snapsync.downloadstore.StagedResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

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
        var failNext = false
        override suspend fun import(ref: AssetRef, resources: List<StagedResource>, creationDate: String): ImportResult {
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
