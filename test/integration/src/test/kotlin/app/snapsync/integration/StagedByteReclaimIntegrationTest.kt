package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.model.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The staged-byte backlog reclaim, driven through the **trigger** rather than through the method.
 *
 * `DownloadController.releaseSettledBytes()` was built, spec'd, and pinned at the store layer (then the
 * `DownloadStoreContract`, now `DownloadServiceTest`) — and never called from anywhere. Every one of those checks stayed green while
 * the leak they describe ran on every install that predates per-asset release: a received photo stored
 * twice, once as the library asset and once as a staged file the OS never reclaims. So these tests
 * deliberately do **not** reach the method. They fire the operating system's foreground entry — the same
 * entry the iOS shell runs on scene activation, over the same composition — and assert the storage the app takes
 * directory's files afterwards. A test that called the method directly would have passed on the broken tree.
 *
 * The seeded state is what a pre-release-era install actually holds, and it is not reachable through the
 * app's own download path: every import there releases its bytes inline, which is the fix that shipped
 * without the backlog pass behind it. So it is written by the one lever that writes app-private state,
 * `olderInstall.stagedBacklog` — an upgraded install's confirmed import whose resource rows, with their staged
 * paths, are still there.
 */
@Verifies(spec = "receiving-photos", requirement = "Received photos do not take up space twice")
class StagedByteReclaimIntegrationTest {

    @Test
    fun a_foreground_entry_reclaims_the_backlog_a_pre_release_install_left_behind() = rigTest {
        createAndJoin()
        val seeded = seedLegacyBacklog("FQ")

        // The trigger, not the method. This is the whole test.
        os.foreground()

        awaitReclaimed(seeded, "foreground entry freed the redundant bytes")
    }

    /**
     * The self-extinguishing property, observed across the real trigger: the first foreground drops the rows
     * that made the work findable, so every later one finds nothing — which is why this needs no flag, marker
     * or run-once bookkeeping to sit on a trigger that fires on every scene activation.
     *
     * The second half is the scoping: an unsettled asset's bytes survive that second pass. They are the only
     * source for its retry (a resource already recorded as staged is never re-downloaded), so a reclaim that
     * reached them would not cost a retry — it would lose the photo silently. The unsettled asset here is one
     * whose import is still open in the photo library: every resource staged, the row not yet confirmed.
     */
    @Test
    fun the_reclaim_extinguishes_itself_and_never_reaches_an_unsettled_row() = rigTest {
        createAndJoin()
        val seeded = seedLegacyBacklog("FQ")
        os.foreground()
        val drained = awaitReclaimed(seeded, "the first pass drained it")

        // A second asset, mid-import: its bytes staged, its row unsettled — exactly what a retry will read.
        foreignDevice(DEV_F, "FR")
        library.suspendNextImport()
        reconcile()
        awaitDownloadQueued()
        stage(wait = false)
        awaitImportParked()
        val partial = storage()
        assertTrue(partial > drained, "precondition: the unsettled asset's bytes are staged")

        os.foreground()

        holdsWithin(what = "an unsettled row keeps its bytes — releasing them loses the photo", read = { storage() }) {
            it >= partial
        }

        // Once the import lands, its own release takes the bytes — the row is settled.
        library.resumeImport(succeeded = true)
        eventually<Long>(read = { storage() }) { it <= drained }
    }

    /**
     * The reclaim is **unconditional**, which is the one thing wiring it beside `reconcile` could easily have
     * got wrong: `reconcile` is gated on the membership's participation direction, and the backlog belongs to
     * the device rather than to any membership. Behind that gate — or behind the flow's active-event guard — a
     * device that left, or that has since joined an upload-only event, would carry its orphaned files forever,
     * because nothing else reaches them (a leave releases only the non-terminal rows it is about to prune, and
     * an imported row is terminal).
     */
    @Test
    fun an_unjoined_device_still_reclaims_its_download_backlog() = rigTest {
        // Deliberately never joined.
        val seeded = seedLegacyBacklog("FQ")

        os.foreground()

        awaitReclaimed(seeded, "no membership is needed to free the disk")
    }

    @Test
    fun an_upload_only_membership_still_reclaims_its_download_backlog() = rigTest {
        createAndJoin(Choices(direction = Direction.UploadOnly))
        val seeded = seedLegacyBacklog("FQ")

        os.foreground()

        awaitReclaimed(seeded, "the direction gate stops the reconcile — it must not stop the reclaim")
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * Leave behind exactly what an install that imported before per-asset release existed leaves — the row
     * terminal, its resource rows surviving, the files on disk — and check the storage the app takes grew by them.
     * Answers the storage with them in it, and how much they take.
     */
    private suspend fun Rig.seedLegacyBacklog(asset: String): Seeded {
        val before = storage()
        val bytes = olderInstall.stagedBacklog(device = DEV_F, asset = asset)
        val with = storage()
        assertEquals(before + bytes, with, "precondition: the files are on the disk")
        return Seeded(with, bytes)
    }

    private class Seeded(val storage: Long, val bytes: Long)

    /** The storage the app takes, as the device's storage page shows it. */
    private suspend fun Rig.storage(): Long = files.appStorageBytes()

    /**
     * The foreground's reclaim runs as one of its concurrent children, so its effect is awaited: the storage fell by at
     * least what the backlog took. Answers the storage once it has.
     */
    private suspend fun Rig.awaitReclaimed(seeded: Seeded, what: String): Long {
        val now = eventually(read = { storage() }) { it <= seeded.storage - seeded.bytes }
        assertTrue(now <= seeded.storage - seeded.bytes, what)
        return now
    }
}
