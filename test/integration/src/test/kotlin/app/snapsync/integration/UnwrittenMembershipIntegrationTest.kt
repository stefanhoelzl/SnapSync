package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.levers.WriteKind
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test

/**
 * **A membership the phone cannot write or read is never half a membership** (capabilities `join-event` and
 * `manage-membership`): a join the server admitted but the phone could not record stays retryable, and a wake that
 * cannot read the membership — the store's file failing, or the phone locked since boot — ends nothing.
 */
class UnwrittenMembershipIntegrationTest {

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "Joining happens only on confirmation and needs a connection",
        scenario = "A failed join is retryable and leaves nothing behind",
    )
    fun a_join_the_phone_cannot_record_stays_retryable_and_joins_on_retry() = behaviour {
        given {
            gateOpenOn(registeredEvent(name = "Anna's Wedding"))
            step(
                "the membership's next write refused",
                act = { databases.fail("ledger.db", "membership", WriteKind.INSERT) },
                until = {},
            )
        }

        whenever { confirmJoin() }
        then {
            event<WorldEvent.DatabaseFaulted> { it.table == "membership" && it.kind == WriteKind.INSERT }
            screen<Layer.JoiningEvent> { (it.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.CommitFailed }
            neverScreen<Layer.Joined>()
        }

        whenever { tap(UiIntent.RetryJoin) }
        then { screen<Layer.Joined>() }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "A membership survives updates, restarts, and a locked phone",
        scenario = "A locked phone never looks like a leave",
    )
    fun a_wake_that_cannot_read_the_membership_ends_nothing() = behaviour {
        given {
            joinedEvent()
            step("the membership's store unreadable from the next launch on", act = {
                databases.unreadable("ledger.db", on = true)
                host.relaunch(scene = false)
            }, until = {})
        }

        whenever { os.backgroundTask(HEARTBEAT_TASK) }
        then {
            never<WorldEvent.BackendReceived> { it.operation == "leaveEvent" }
            neverScreen<Layer.CreateEvent>()
        }

        whenever {
            databases.unreadable("ledger.db", on = false)
            os.foreground()
        }
        then { screen<Layer.Joined>() }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "A membership survives updates, restarts, and a locked phone",
        scenario = "A locked phone never looks like a leave",
    )
    fun a_wake_on_a_phone_locked_since_boot_ends_nothing() = behaviour {
        given {
            joinedEvent()
            step("the phone restarted and is not unlocked yet", act = {
                device.lock()
                host.relaunch(scene = false)
            }, until = {})
        }

        whenever { os.backgroundTask(HEARTBEAT_TASK) }
        then {
            never<WorldEvent.BackendReceived> { it.operation == "leaveEvent" }
            neverScreen<Layer.CreateEvent>()
        }

        whenever {
            device.unlock()
            os.foreground()
        }
        then { screen<Layer.Joined>() }
    }
}
