package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import kotlin.test.Test

/**
 * **A membership this build refuses** (capability `join-event`, "An unknown range shares nothing"): a share range an
 * older build stored that this build's rules forbid. Nothing is shared for it, and scanning the event's invite again
 * joins it afresh — the join replaces the row rather than failing on it, which left the member in no event and unable to
 * join any.
 *
 * The refused row is an older build's (`olderInstall.refusedShareRange`); the relaunch after it is the update.
 */
class RefusedMembershipIntegrationTest {

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The shared capture range is always bounded and chosen by the member",
        scenario = "An unknown range shares nothing",
    )
    fun a_refused_range_shares_nothing_until_the_member_joins_again() = behaviour {
        val link = given {
            step("only the app uploads", act = { devControls.pin(app = true, extension = false) }, until = {})
            val link = inviteFor(joinedEvent())
            step("an older build's range, read by this one", act = {
                olderInstall.refusedShareRange()
                host.relaunch()
                os.foreground()
            }, until = {
                screen<Layer.CreateEvent>()
                settled { true }
            })
            link
        }

        whenever {
            takePhoto("P1")
            os.foreground()
        }
        then {
            never<WorldEvent.SessionTransferCreated>()
            never<WorldEvent.UploadJobCreated>()
            settled { true }
        }

        whenever { openLink(link) }
        then { screen<Layer.JoiningEvent> { (it.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.Ready } }
        whenever { confirmJoin() }
        then {
            screen<Layer.Joined>()
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("P1") }
        }
    }
}
