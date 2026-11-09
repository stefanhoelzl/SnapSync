package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.BackendFault
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import kotlin.test.Test

/**
 * **What the switch question rests on, as the service answers it** (capability `invite-link`): opening another event's
 * invite while joined reads both events. An invite whose event the service does not answer for keeps the question —
 * nothing is known about what the member would switch to — and a joined event the service reports deleted on that very
 * read is left without one.
 */
class SwitchReadIntegrationTest {

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Opening another event's invite asks before switching",
    )
    fun an_invite_whose_event_the_service_fails_to_load_leaves_the_member_where_they_are() = behaviour {
        val link = given {
            val link = inviteFor(registeredEvent(name = SKI_TRIP))
            joinedEvent(name = ANAS_30TH)
            step("the service failing the next read of an event", act = {
                backend.fail(GET_EVENT, BackendFault.SERVER_ERROR, times = 1)
            }, until = {})
            link
        }

        whenever { openLink(link) }
        then {
            screen<Layer.Joined> { it.membership.name == ANAS_30TH && it.pendingSwitch?.phase == JoinPhase.LoadFailed }
            never<WorldEvent.BackendReceived> { it.operation == LEAVE }
            neverScreen<Layer.JoiningEvent>()
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Opening another event's invite asks before switching",
        scenario = "A deleted event is switched away from without asking",
    )
    fun an_ended_event_the_service_reports_deleted_as_the_invite_opens_is_left_without_a_question() = behaviour {
        val link = given {
            val link = inviteFor(registeredEvent(name = SKI_TRIP))
            val current = joinedEvent(name = ANAS_30TH, startsAt = SHORT_START, endsAt = SHORT_END)
            laterInThePocket(AFTER_THE_END)
            inFront()
            step("its photos deleted while the app is in front", act = { backend.complete(current) }, until = {})
            link
        }

        whenever { openLink(link) }
        then {
            screen<Layer.JoiningEvent> { (it.phase as? JoinPhase.Detailed)?.eventName == SKI_TRIP }
            event<WorldEvent.BackendReceived> { it.operation == LEAVE }
            neverScreen<Layer.Joined> { it.pendingSwitch?.phase is JoinPhase.Detailed }
        }
    }

    private companion object {
        const val ANAS_30TH = "Ana's 30th"
        const val SKI_TRIP = "Ski Trip"
        const val LEAVE = "leaveEvent"
        const val GET_EVENT = "getEvent"

        /** A short range around the start of every behaviour's clock (1 June noon). */
        const val SHORT_START = "2026-05-25T00:00:00"
        const val SHORT_END = "2026-06-02T00:00:00"

        /** Past [SHORT_END], and its open-until date with it; long before its photos may be deleted. */
        const val AFTER_THE_END = "2026-06-04T12:00:00Z"
    }
}
