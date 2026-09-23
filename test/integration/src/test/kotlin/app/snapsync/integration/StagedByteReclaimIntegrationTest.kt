package app.snapsync.integration

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The staged-byte backlog reclaim, driven through the **trigger** rather than through the method
 * (capability `download-store`, requirement "Staged bytes are released only once their row is settled").
 *
 * `DownloadController.releaseSettledBytes()` was built, spec'd, and pinned at the store layer by
 * `DownloadStoreContract` — and never called from anywhere. Every one of those checks stayed green while
 * the leak they describe ran on every install that predates per-asset release: a received photo stored
 * twice, once as the library asset and once as a staged file the OS never reclaims. So these tests
 * deliberately do **not** reach the method. They fire the operating system's foreground entry — the same
 * entry the iOS shell runs on scene activation, over the same composition — and assert the staging
 * directory's files afterwards. A test that called the method directly would have passed on the broken tree.
 *
 * The seeded state is what a pre-release-era install actually holds, and it is not reachable through the
 * app's own download path: every import there releases its bytes inline, which is the fix that shipped
 * without the backlog pass behind it. So it is written by the one lever that writes app-private state,
 * `staging/seed-legacy-backlog` — an upgraded install's confirmed import whose resource rows, with their staged
 * paths, are still there.
 */
class StagedByteReclaimIntegrationTest {

    @Test
    fun a_foreground_entry_reclaims_the_backlog_a_pre_release_install_left_behind() = rigTest {
        createAndJoin()
        val paths = seedLegacyBacklog("FQ")

        // The trigger, not the method. This is the whole test.
        os("app", "onForeground")

        awaitReclaimed(paths, "foreground entry freed the redundant bytes")
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
        val paths = seedLegacyBacklog("FQ")
        os("app", "onForeground")
        awaitReclaimed(paths, "the first pass drained it")

        // A second asset, mid-import: its bytes staged, its row unsettled — exactly what a retry will read.
        foreignDevice("DEV-F", "FR")
        device("import/suspend-next")
        reconcile()
        stage(wait = false)
        device("import/await-parked")
        val partial = stagedFiles()
        assertTrue(partial.isNotEmpty(), "precondition: the unsettled asset's bytes are staged")

        os("app", "onForeground")

        stagingHoldsWithin(partial, "an unsettled row keeps its bytes — releasing them loses the photo")
        assertTrue(stagedFiles().none { it in paths }, "and the settled backlog stays gone")

        // Once the import lands, its own release takes the bytes — the row is settled.
        device("import/resume", "succeeded" to "true")
        eventually(read = { stagedFiles() }) { files -> files.none { it in partial } }
        Unit
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
        val paths = seedLegacyBacklog("FQ")

        os("app", "onForeground")

        awaitReclaimed(paths, "no membership is needed to free the disk")
    }

    @Test
    fun an_upload_only_membership_still_reclaims_its_download_backlog() = rigTest {
        createAndJoin("direction" to "upload")
        val paths = seedLegacyBacklog("FQ")

        os("app", "onForeground")

        awaitReclaimed(paths, "the direction gate stops the reconcile — it must not stop the reclaim")
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * Leave behind exactly what an install that imported before per-asset release existed leaves — the row
     * terminal, its resource rows surviving, the files on disk — and check the files really are there.
     */
    private suspend fun Rig.seedLegacyBacklog(asset: String): Set<String> {
        val paths = deviceJson("staging/seed-legacy-backlog", "device" to "DEV-F", "asset" to asset)
            .getValue("staged").jsonArray.mapTo(mutableSetOf()) { it.jsonPrimitive.content }
        assertEquals(2, paths.size, "precondition: a primary and a live resource were staged")
        assertTrue(stagedFiles().containsAll(paths), "precondition: the files are on the disk")
        return paths
    }

    private suspend fun Rig.stagedFiles(): Set<String> =
        deviceJson("staging").getValue("files").jsonArray.mapTo(mutableSetOf()) { it.jsonPrimitive.content }

    /** The foreground's reclaim runs as one of its concurrent children, so its effect is awaited. */
    private suspend fun Rig.awaitReclaimed(paths: Set<String>, what: String) {
        val files = eventually(read = { stagedFiles() }) { files -> files.none { it in paths } }
        assertTrue(files.none { it in paths }, what)
    }

    /** The staging directory keeps every one of [files] for the whole window — a true negative. */
    private suspend fun Rig.stagingHoldsWithin(files: Set<String>, what: String) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + 500.milliseconds
        while (deadline.hasNotPassedNow()) {
            assertTrue(stagedFiles().containsAll(files), what)
            kotlinx.coroutines.delay(50.milliseconds)
        }
    }
}
