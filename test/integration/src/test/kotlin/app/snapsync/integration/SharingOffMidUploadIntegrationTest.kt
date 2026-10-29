package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.sharing.Direction
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * **Switching sharing off withdraws the upload under way** (change `correct-finished-return-and-sharing-off`): the
 * photo whose transfer is running is never offered to the event, no new upload starts, and the joined screen shows no
 * upload work for it — no arrow, no count, and "Not sharing or receiving" when receiving is off too — while its
 * transfer has not finished.
 */
class SharingOffMidUploadIntegrationTest {

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "A settings change takes effect as soon as it applies",
        scenario = "Turning sharing off withdraws the upload under way",
    )
    fun turning_sharing_off_withdraws_the_upload_under_way_and_starts_no_other() = behaviour {
        given { uploading() }

        whenever {
            reconfigure(Choices(direction = Direction.DownloadOnly))
            takePhoto(NEXT)
            uploadSession.complete()
            os.foreground()
            advance(STATUS_POLL)
        }
        then {
            never<WorldEvent.ManifestPublished> { MINE in it.added }
            never<WorldEvent.SessionTransferCreated> { it.key == primaryKey(NEXT) }
            screen<Layer.Joined> { !it.membership.shares && it.counts?.shared == DirectionCount.Off }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen shows one status line in a fixed priority",
        scenario = "Switching both directions off mid-upload is said at once",
    )
    fun switching_both_directions_off_mid_upload_reads_not_sharing_or_receiving() = behaviour {
        given { uploading() }

        whenever { reconfigure(Choices(direction = Direction.Neither)) }
        then { screen<Layer.Joined> { it.health == SyncHealth.Inactive } }

        whenever { readAgain() }
        then {
            neverScreen<Layer.Joined> { it.health != SyncHealth.Inactive }
            never<WorldEvent.BytesStored> { it.asset == MINE }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "Direction arrows show the work left and the transfer running",
        scenario = "Switching sharing off mid-upload clears the upload arrow",
    )
    fun switching_sharing_off_mid_upload_clears_the_upload_arrow() = behaviour {
        given { uploading() }

        whenever { reconfigure(Choices(direction = Direction.DownloadOnly)) }
        then { screen<Layer.Joined> { !it.membership.shares && it.health == SyncHealth.InSync } }

        whenever { readAgain() }
        then {
            neverScreen<Layer.Joined> { it.health != SyncHealth.InSync }
            never<WorldEvent.BytesStored> { it.asset == MINE }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen counts the photos shared and received",
        scenario = "Switching sharing off mid-upload counts nothing as shared",
    )
    fun switching_sharing_off_mid_upload_counts_nothing_as_shared() = behaviour {
        given { uploading(waiting = listOf("W1", "W2")) }

        whenever { reconfigure(Choices(direction = Direction.DownloadOnly)) }
        then { screen<Layer.Joined> { !it.membership.shares && it.counts?.shared == DirectionCount.Off } }

        whenever { readAgain() }
        then {
            neverScreen<Layer.Joined> { it.counts?.shared != DirectionCount.Off }
            never<WorldEvent.BytesStored> { it.asset == MINE }
            settled { true }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * A sharing and receiving member whose photo [MINE] is uploading — its transfer created and not finished — with
     * [waiting] taken alongside it, still to share. Only the app uploads, so the transfer runs where the test sees it.
     */
    private suspend fun Given.uploading(waiting: List<String> = emptyList()) {
        step("only the app uploads", act = { devControls.pin(app = true, extension = false) }, until = {})
        joinedEvent()
        step("$MINE taken, its transfer created", act = {
            takePhoto(MINE)
            waiting.forEach { takePhoto(it) }
            os.foreground()
        }, until = {
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey(MINE) }
            settled { true }
        })
    }

    /** The app in front long enough for the joined screen's status poll to read the device again. */
    private suspend fun Acts.readAgain() {
        os.foreground()
        advance(STATUS_POLL)
    }

    private companion object {
        const val MINE = "MINE"
        const val NEXT = "NEXT"

        /** Long enough for the joined screen's status poll (every 2 s) to read again. */
        val STATUS_POLL = 5.seconds
    }
}
