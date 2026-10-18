package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Expectations
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.sharing.Direction
import app.snapsync.levers.WorldEvent
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The staged-byte backlog reclaim, driven through the **trigger** rather than through the method.
 *
 * `DownloadController.releaseSettledBytes()` was built, spec'd, and pinned at the store layer (then the
 * `DownloadStoreContract`, now `DownloadServiceTest`) — and never called from anywhere. Every one of those checks stayed green while
 * the leak they describe ran on every install that predates per-asset release: a received photo stored
 * twice, once as the library asset and once as a staged file the OS never reclaims. So these tests
 * deliberately do **not** reach the method. They fire the operating system's foreground entry — the same
 * entry the iOS shell runs on scene activation, over the same composition — and judge the storage the app takes
 * afterwards. A test that called the method directly would have passed on the broken tree.
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
    fun a_foreground_entry_reclaims_the_backlog_a_pre_release_install_left_behind() = behaviour {
        val seeded = given {
            joinedEvent()
            legacyBacklog("FQ")
        }

        // The trigger, not the method. This is the whole test.
        whenever { os.foreground() }
        then { reclaimed(seeded) } // foreground entry freed the redundant bytes
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
    fun the_reclaim_extinguishes_itself_and_never_reaches_an_unsettled_row() = behaviour {
        val (drained, partial) = given {
            joinedEvent()
            val seeded = legacyBacklog("FQ")
            lateinit var first: Seen<WorldEvent.Settled>
            step("the first pass", act = { os.foreground() }, until = { first = reclaimed(seeded) })

            // A second asset, mid-import: its bytes staged, its row unsettled — exactly what a retry will read.
            downloadStarted(asset = "FR")
            lateinit var parked: Seen<WorldEvent.Settled>
            step(
                "its bytes staged, its import open in the library",
                act = {
                    library.suspendNextImport()
                    downloadSession.finish()
                },
                until = {
                    event<WorldEvent.LibraryImportParked> { it.asset == "FR" }
                    parked = settled { true }
                },
            )
            first.value.storage() to parked.value.storage()
        }
        assertTrue(partial > drained, "precondition: the unsettled asset's bytes are staged")

        whenever { os.foreground() }
        // an unsettled row keeps its bytes — releasing them loses the photo
        then { settled { it.storage() >= partial } }

        // Once the import lands, its own release takes the bytes — the row is settled.
        whenever { library.resumeImport(succeeded = true) }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == imported("FR") }
            settled { it.storage() <= drained }
        }
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
    fun an_unjoined_device_still_reclaims_its_download_backlog() = behaviour {
        // Deliberately never joined.
        val seeded = given { legacyBacklog("FQ") }

        whenever { os.foreground() }
        then { reclaimed(seeded) } // no membership is needed to free the disk
    }

    @Test
    fun an_upload_only_membership_still_reclaims_its_download_backlog() = behaviour {
        val seeded = given {
            joinedEvent(Choices(direction = Direction.UploadOnly))
            legacyBacklog("FQ")
        }

        whenever { os.foreground() }
        // the direction gate stops the reconcile — it must not stop the reclaim
        then { reclaimed(seeded) }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * Leave behind exactly what an install that imported before per-asset release existed leaves — the row
     * terminal, its resource rows surviving, the files on disk. Answers the storage the app takes with them in it, and
     * how much they take.
     */
    private suspend fun Given.legacyBacklog(asset: String): Seeded {
        lateinit var settle: Seen<WorldEvent.Settled>
        val bytes = step(
            "a pre-release install's backlog of $asset",
            act = { olderInstall.stagedBacklog(device = DEV_F, asset = asset) },
            until = { settle = settled { true } },
        )
        val with = settle.value.storage()
        assertTrue(bytes > 0 && with >= bytes, "precondition: the files are on the disk ($bytes of $with)")
        return Seeded(with, bytes)
    }

    private class Seeded(val storage: Long, val bytes: Long)

    /**
     * The foreground's reclaim runs as one of its concurrent children, so its effect is judged at the settle: the
     * storage fell by at least what the backlog took. Answers that settle.
     */
    private fun Expectations.reclaimed(seeded: Seeded) =
        settled { it.storage() <= seeded.storage - seeded.bytes }

    /** The storage the app takes, as the device's storage page shows it. */
    private fun WorldEvent.Settled.storage(): Long = checkNotNull(state.appStorageBytes)
}
