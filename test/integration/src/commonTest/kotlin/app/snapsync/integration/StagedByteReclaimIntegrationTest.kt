package app.snapsync.integration

import app.snapsync.model.Direction
import app.snapsync.ports.AssetRef
import app.snapsync.ports.PlannedResource
import app.snapsync.world.World
import app.snapsync.world.worldTest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The staged-byte backlog reclaim, driven through the **trigger** rather than through the method
 * (capability `download-store`, requirement "Staged bytes are released only once their row is settled").
 *
 * `DownloadController.releaseSettledBytes()` was built, spec'd, and pinned at the store layer by
 * `DownloadStoreContract` — and never called from anywhere. Every one of those checks stayed green while
 * the leak they describe ran on every install that predates per-asset release: a received photo stored
 * twice, once as the library asset and once as a staged file the OS never reclaims. So these tests
 * deliberately do **not** call `releaseSettledBytes`. They drive `flow/Foreground` — the same instance
 * the iOS shell runs on scene activation, composed by the same `snapSyncApp` — and assert the world's
 * "disk" afterwards. A test that called the method directly would have passed on the broken tree.
 *
 * The seeded state is what a pre-release-era install actually holds, and it is not reachable through the
 * world's own download path: every import there releases its bytes inline, which is the fix that shipped
 * without the backlog pass behind it. So it is written onto the store directly — a confirmed import whose
 * resource rows, with their staged paths, are still there.
 */
class StagedByteReclaimIntegrationTest {

    private val ref = AssetRef("DEV-F", "FQ")

    /**
     * Leave behind exactly what an install that imported before per-asset release existed leaves: the row
     * is terminal and carries its marker, its resource rows survive, and the files are on the "disk".
     */
    private suspend fun World.seedPreReleaseBacklog(ref: AssetRef): Set<String> {
        val primaryKey = "${ref.sourceAssetId}-primary.heic"
        val liveKey = "${ref.sourceAssetId}-live.mov"
        val paths = listOf(
            "${stagedBytes.stagingRoot()}$primaryKey",
            "${stagedBytes.stagingRoot()}$liveKey",
        )
        downloadStore.plan(
            ref,
            World.DEFAULT_DATE,
            listOf(
                PlannedResource(primaryKey, "https://world.edge/p", "primary", "image/heic", "IMG.HEIC"),
                PlannedResource(liveKey, "https://world.edge/l", "live", "video/quicktime", "IMG.MOV"),
            ),
        )
        downloadStore.markStaged(ref, primaryKey, paths[0])
        downloadStore.markStaged(ref, liveKey, paths[1])
        downloadStore.markImported(ref, "LOCAL-${ref.sourceAssetId}")
        stagedBytes.files += paths
        return paths.toSet()
    }

    /** The precondition every test here needs: the backlog is genuinely present before the trigger runs. */
    private suspend fun World.assertBacklogPresent(paths: Set<String>) {
        assertTrue(stagedBytes.files.containsAll(paths), "precondition: the files are on the disk")
        assertEquals(
            paths,
            downloadStore.stagedPathsOfImportedAssets().toSet(),
            "precondition: the store still points at them — the reclaim has work to find",
        )
    }

    @Test
    fun a_foreground_entry_reclaims_the_backlog_a_pre_release_install_left_behind() = worldTest {
        val w = World(this)
        w.provision("E")
        val paths = w.seedPreReleaseBacklog(ref)
        w.assertBacklogPresent(paths)

        // The trigger, not the method. This is the whole test.
        w.core.foregroundFlow.run()

        assertTrue(
            w.stagedBytes.files.none { it in paths },
            "foreground entry freed the redundant bytes",
        )
        assertTrue(w.downloadStore.isImported(ref), "while the asset row stays terminal")
        assertTrue(
            "LOCAL-${ref.sourceAssetId}" in w.downloadStore.suppressedLocalIds(),
            "and its marker survives — it is what stops the photo being uploaded back into the event",
        )
    }

    /**
     * The self-extinguishing property, observed across the real trigger rather than at the store: the
     * first foreground drops the rows that made the work findable, so every later one finds nothing —
     * which is why this needs no flag, marker or run-once bookkeeping to sit on a trigger that fires on
     * every scene activation.
     *
     * The second half is the scoping: a half-staged asset seeded in between keeps its bytes across that
     * second pass. They are the only source for its retry (a resource already recorded as staged is
     * never re-downloaded), so a reclaim that reached them would not cost a retry — it would lose the
     * photo silently.
     */
    @Test
    fun the_reclaim_extinguishes_itself_and_never_reaches_an_unsettled_row() = worldTest {
        val w = World(this)
        w.provision("E")
        val paths = w.seedPreReleaseBacklog(ref)
        w.core.foregroundFlow.run()
        assertTrue(w.downloadStore.stagedPathsOfImportedAssets().isEmpty(), "the first pass drained it")

        // A second asset, mid-download: one resource staged, one still outstanding — so it is neither
        // imported nor even importable, and its staged half is exactly what a retry will read.
        val partial = AssetRef("DEV-F", "FR")
        val stagedKey = "${partial.sourceAssetId}-primary.heic"
        val outstandingKey = "${partial.sourceAssetId}-live.mov"
        val partialPath = "${w.stagedBytes.stagingRoot()}$stagedKey"
        w.downloadStore.plan(
            partial,
            World.DEFAULT_DATE,
            listOf(
                PlannedResource(stagedKey, "https://world.edge/p2", "primary", "image/heic", "IMG2.HEIC"),
                PlannedResource(outstandingKey, "https://world.edge/l2", "live", "video/quicktime", "IMG2.MOV"),
            ),
        )
        w.downloadStore.markStaged(partial, stagedKey, partialPath)
        w.stagedBytes.files += partialPath

        w.core.foregroundFlow.run()

        assertTrue(w.downloadStore.stagedPathsOfImportedAssets().isEmpty(), "the second pass found nothing")
        assertTrue(
            partialPath in w.stagedBytes.files,
            "an unsettled row keeps its bytes — releasing them does not cost a retry, it loses the photo",
        )
        assertTrue(w.stagedBytes.files.none { it in paths }, "and the settled backlog stays gone")
    }

    /**
     * The reclaim is **unconditional**, which is the one thing wiring it beside `reconcile` could easily
     * have got wrong: `reconcile` is gated on the membership's participation direction, and the backlog
     * belongs to the device rather than to any membership. Behind that gate — or behind the flow's
     * `activeEventId()` guard — a device that left, or that has since joined an upload-only event, would
     * carry its orphaned files forever, because nothing else reaches them (`onLeaveOrSwitch` releases
     * only the non-terminal rows it is about to prune, and an imported row is terminal).
     */
    @Test
    fun an_unjoined_device_still_reclaims_its_download_backlog() = worldTest {
        val w = World(this) // deliberately never provisioned
        val paths = w.seedPreReleaseBacklog(ref)
        w.assertBacklogPresent(paths)

        w.core.foregroundFlow.run()

        assertTrue(w.stagedBytes.files.none { it in paths }, "no membership is needed to free the disk")
    }

    @Test
    fun an_upload_only_membership_still_reclaims_its_download_backlog() = worldTest {
        val w = World(this)
        w.provision("E", direction = Direction.UploadOnly)
        val paths = w.seedPreReleaseBacklog(ref)
        w.assertBacklogPresent(paths)

        w.core.foregroundFlow.run()

        assertTrue(
            w.stagedBytes.files.none { it in paths },
            "the direction gate stops the reconcile — it must not stop the reclaim",
        )
    }
}
