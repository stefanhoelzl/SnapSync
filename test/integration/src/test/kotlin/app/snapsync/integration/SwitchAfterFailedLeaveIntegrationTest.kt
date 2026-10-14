package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.levers.WriteKind
import app.snapsync.ui.state.Layer
import kotlin.test.Test

/**
 * **A join is a switch whenever the device is still joined**: a leave whose membership the store would not delete
 * leaves the device fully joined to the old event, and the next join then switches — it tells the backend this device
 * left the old event — instead of entering the new event beside a membership it never ended.
 */
class SwitchAfterFailedLeaveIntegrationTest {

    @Test
    @Verifies(spec = "manage-membership", requirement = "Leaving asks first, then is instant and works offline")
    fun a_join_after_a_leave_that_could_not_clear_switches_out_of_the_old_event() = behaviour {
        val next = given {
            val next = registeredEvent(name = "Anna's Wedding")
            joinedEvent()
            step(
                "the membership's next delete refused",
                act = { databases.fail("ledger.db", "membership", WriteKind.DELETE) },
                until = {},
            )
            next
        }

        // A headless join: the gate leaves the current event first — whose clear fails — then joins.
        whenever { openLink(inviteLink(next, autoJoin = true)) }
        then {
            inOrder {
                event<WorldEvent.DatabaseFaulted> { it.table == "membership" && it.kind == WriteKind.DELETE }
                screen<Layer.Joined> { it.membership.eventId == next }
            }
            event<WorldEvent.BackendReceived> { it.operation == "leaveEvent" }
        }
    }
}
