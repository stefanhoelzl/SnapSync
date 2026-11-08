package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.sharing.Direction
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Arrow
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncCounts
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * **What there is to receive is what the others declared** (change `count-declared-photos`): a fellow member's photos
 * still uploading on its phone count toward this member's received total, the line says it waits for them, and they
 * stop counting when that member leaves before they arrive, and the member's own photos never count among them, not
 * even right after they are withdrawn — read off the joined screen.
 */
class DeclaredPhotosIntegrationTest {

    @Test
    @Verifies(spec = "sync-status", requirement = "Progress counts only what this membership shares")
    @Verifies(
        spec = "sync-status",
        requirement = "Direction arrows show the work left and the transfer running",
        scenario = "Waiting for the others' photos",
    )
    @Verifies(
        spec = "sync-status",
        requirement = "Progress counts only what this membership shares",
        scenario = "Photos still uploading count toward what there is to receive",
    )
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen counts the photos shared and received",
        scenario = "The receiver's total matches the sharer's",
    )
    fun photos_still_uploading_elsewhere_are_counted_and_waited_for_until_they_land() = behaviour {
        // For "The receiver's total matches the sharer's", the receiver's part: what there is to receive is everything
        // the sharer declared, landed or not — 7 of 10. The sharer's part is the test below; the service's total is
        // `declared-total.test.ts`'s.
        val all = (1..10).map { "P$it" }
        given {
            joinedEvent()
            downloadsPlanned(all, uploading = listOf("P8", "P9", "P10"))
        }

        lateinit var waiting: Seen<Layer.Joined>
        whenever { arrivedAndCounted() }
        then { waiting = screen<Layer.Joined> { it.received == DirectionCount.Progress(done = 7, total = 10) } }
        assertEquals(
            SyncHealth.Syncing(upload = Arrow.HIDDEN, download = Arrow.STATIC, waitingForOthers = true),
            waiting.value.health,
            "7 of 10 received, and nothing left this device can do",
        )

        // The rest finishes uploading.
        whenever {
            publicBackend.foreignDevice(DEV_F, all)
            os.foreground()
        }
        then {
            event<WorldEvent.DownloadStarted>()
            settled { true }
        }

        whenever { arrivedAndCounted() }
        then {
            screen<Layer.Joined> {
                it.health == SyncHealth.InSync && it.received == DirectionCount.Progress(done = 10, total = 10)
            }
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen counts the photos shared and received",
        scenario = "The receiver's total matches the sharer's",
    )
    fun a_sharer_counts_what_it_declared_and_declares_it_all_before_it_is_shared() = behaviour {
        // The sharer's part: its own total is every photo it shares, and it declares every one of them to the event
        // before their bytes land — the declaration the receiver's total is counted from (the test above).
        val all = (1..4).map { "S$it" } // the app session's live transfers at once
        given {
            step("only the app uploads", act = { devControls.pin(app = true, extension = false) }, until = {})
            joinedEvent()
        }

        whenever {
            all.forEach { takePhoto(it) }
            os.foreground()
        }
        then {
            event<WorldEvent.ManifestPublished> { it.added.containsAll(all) }
            times(all.size) { event<WorldEvent.SessionTransferCreated>() }
            settled { true }
        }

        whenever {
            uploadSession.complete(primaryKey("S1"))
            uploadSession.complete(primaryKey("S2"))
            advance(SCREEN_REREAD)
        }
        then { screen<Layer.Joined> { it.counts?.shared == DirectionCount.Progress(done = 2, total = 4) } }
    }

    @Test
    @Verifies(spec = "sync-status", requirement = "Progress counts only what this membership shares")
    @Verifies(
        spec = "sync-status",
        requirement = "Progress counts only what this membership shares",
        scenario = "A departing member's undelivered photos stop counting",
    )
    fun a_member_leaving_before_its_photos_arrive_takes_them_out_of_the_total() = behaviour {
        given {
            joinedEvent()
            downloadsPlanned(listOf("A", "B", "C", "D"), uploading = listOf("C", "D"))
            step("what was complete arrived", act = { arrivedAndCounted() }, until = {
                screen<Layer.Joined> { it.received == DirectionCount.Progress(done = 2, total = 4) }
                settled { true }
            })
        }

        whenever {
            publicBackend.foreignDeviceLeave(DEV_F)
            os.foreground()
            advance(SCREEN_REREAD)
        }
        // What arrived stays counted; what never will is gone.
        then {
            screen<Layer.Joined> {
                it.health == SyncHealth.InSync && it.received == DirectionCount.Progress(done = 2, total = 2)
            }
        }
    }

    @Test
    @Verifies(spec = "sync-status", requirement = "Direction arrows show the work left and the transfer running")
    @Verifies(
        spec = "sync-status",
        requirement = "Direction arrows show the work left and the transfer running",
        scenario = "Photos leaving",
    )
    fun photos_leaving_while_everything_has_arrived_are_on_their_way_not_waited_for() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            downloadsPlanned(listOf("A"))
            step("it arrived", act = { downloadSession.finish() }, until = {
                event<WorldEvent.LibraryAssetCreated>()
                settled { true }
            })
            photos("MINE")
        }

        lateinit var running: Seen<Layer.Joined>
        whenever {
            os.foreground()
            cycle()
            advance(SCREEN_REREAD)
        }
        then { running = screen<Layer.Joined> { (it.health as? SyncHealth.Syncing)?.upload == Arrow.PULSING } }
        assertEquals(
            SyncHealth.Syncing(upload = Arrow.PULSING, download = Arrow.HIDDEN),
            running.value.health,
            "only this device's upload runs: the line is the one for a running transfer, never waiting for photos",
        )
    }

    // The member's own photo leaves what they declare when they switch sharing off, while the union read before it still
    // holds it in the event's total: their own photo was briefly counted as one the others had yet to send.
    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen counts the photos shared and received",
        scenario = "Sharing switched off",
    )
    fun switching_sharing_off_counts_only_the_others_photos_as_received() = behaviour {
        given {
            step("only the app uploads", act = { devControls.pin(app = true, extension = false) }, until = {})
            joinedEvent()
            step("MINE taken, its transfer created", act = {
                takePhoto("MINE")
                os.foreground()
            }, until = {
                event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("MINE") }
                settled { true }
            })
            step("MINE shared", act = { uploadSession.complete() }, until = {
                event<WorldEvent.BytesStored> { it.asset == "MINE" }
                settled { true }
            })
            downloadsPlanned(listOf("A", "B"))
            step("the others' photos arrived", act = { arrivedAndCounted() }, until = {
                screen<Layer.Joined> { it.received == DirectionCount.Progress(done = 2, total = 2) }
                settled { true }
            })
        }

        whenever {
            reconfigure(Choices(direction = Direction.DownloadOnly))
            advance(SCREEN_REREAD)
        }
        then {
            screen<Layer.Joined> {
                !it.membership.shares && it.health == SyncHealth.InSync &&
                    it.counts == SyncCounts(shared = DirectionCount.Off, received = DirectionCount.Progress(2, 2))
            }
            neverScreen<Layer.Joined> { (it.received as? DirectionCount.Progress)?.total != 2 }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen counts the photos shared and received",
        scenario = "Receiving switched off keeps what arrived",
    )
    fun switching_receiving_off_keeps_the_count_of_what_arrived() = behaviour {
        val arrived = (1..40).map { "R$it" }
        given {
            joinedEvent()
            downloadsPlanned(arrived)
            // The downloads are planned in bounded batches: each finished batch plans the next.
            step("the others' 40 photos arrived", act = { repeat(ARRIVAL_BATCHES) { arrivedAndCounted() } }, until = {
                screen<Layer.Joined> { it.received == DirectionCount.Progress(done = 40, total = 40) }
                settled { true }
            })
        }

        // Receiving off stops what is still to come and keeps what came: nothing is arriving, so the count says
        // receiving is off — and that the 40 photos are still here.
        whenever {
            reconfigure(Choices(direction = Direction.UploadOnly))
            advance(SCREEN_REREAD)
        }
        then {
            screen<Layer.Joined> { !it.membership.receives && it.received == DirectionCount.Kept(40) }
            settled { true }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * A fellow member declares [assets] — [uploading]'s still on its phone — and the foreground's reconcile plans the
     * downloads of the complete ones.
     */
    private suspend fun Given.downloadsPlanned(assets: List<String>, uploading: List<String> = emptyList()) = step(
        "$DEV_F declares ${assets.joinToString()}",
        act = {
            publicBackend.foreignDevice(DEV_F, assets, uploading = uploading)
            os.foreground()
        },
        until = {
            event<WorldEvent.DownloadStarted>()
            settled { true }
        },
    )

    /** Every planned download arrives, and the joined screen re-reads the counts the arrivals changed. */
    private suspend fun Acts.arrivedAndCounted() {
        downloadSession.finish()
        advance(SCREEN_REREAD)
    }

    /** What the joined screen counts as received. */
    private val Layer.Joined.received get() = counts?.received

    private companion object {
        /** Past the joined screen's next read of the counts. */
        val SCREEN_REREAD = 5.seconds

        /** Enough finished batches for 40 planned downloads to have all arrived. */
        const val ARRIVAL_BATCHES = 10
    }
}
