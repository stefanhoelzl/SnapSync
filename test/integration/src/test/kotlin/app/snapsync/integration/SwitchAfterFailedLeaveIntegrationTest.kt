package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.levers.WriteKind
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test

/**
 * **A switch whose leave fails keeps the member fully joined**: a leave whose membership the store would not delete
 * leaves the device in the old event, still asked about the switch — it never enters the new event beside a
 * membership it never ended.
 */
class SwitchAfterFailedLeaveIntegrationTest {

    @Test
    @Verifies(spec = "manage-membership", requirement = "Leaving asks first, then is instant and works offline")
    fun a_switch_whose_leave_cannot_clear_stays_in_the_old_event() = behaviour {
        val next = given {
            val next = registeredEvent(name = "Anna's Wedding")
            joinedEvent()
            val link = inviteFor(next)
            step(
                "the switch asked",
                act = { openLink(link) },
                until = { screen<Layer.Joined> { it.pendingSwitch?.eventId == next } },
            )
            step(
                "the membership's next delete refused",
                act = { databases.fail("ledger.db", "membership", WriteKind.DELETE) },
                until = {},
            )
            next
        }

        whenever { tap(UiIntent.ConfirmSwitch) }
        then {
            event<WorldEvent.DatabaseFaulted> { it.table == "membership" && it.kind == WriteKind.DELETE }
            neverScreen<Layer.Joined> { it.membership.eventId == next }
            neverScreen<Layer.JoiningEvent>()
        }
    }
}
