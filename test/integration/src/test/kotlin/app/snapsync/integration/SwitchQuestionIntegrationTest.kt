package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Expectations
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.BackendFault
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test

/**
 * **Opening another event's invite asks before switching** (capability `invite-link`), over the real composition: a
 * member of "Ana's 30th" opens the invite of "Ski Trip". The question names both events and is asked whenever leaving
 * could lose something — a member still sharing, or a service the phone cannot reach — and is skipped only when the
 * joined event is finished for everyone or its photos were deleted (decision record `changes/back-dated-events`, D8).
 *
 * Whether the member left is read off the backend (`leaveEvent` reaching it) and the screen; a quiet switch to an
 * invite that does not load is not here — it is the one case the quiet leave runs ahead of the load.
 */
class SwitchQuestionIntegrationTest {

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Opening another event's invite asks before switching",
        scenario = "The switch question names both events",
    )
    fun the_switch_question_names_the_joined_event_and_the_invited_one() = behaviour {
        val link = given { joinedTo30thWithSkiTripInvite() }

        whenever { openLink(link) }
        then {
            screen<Layer.Joined> { it.membership.name == ANAS_30TH && it.asksToSwitchTo(SKI_TRIP) }
            stays()
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Opening another event's invite asks before switching",
        scenario = "Declining keeps the current event",
    )
    fun declining_the_switch_keeps_the_member_in_the_joined_event_unchanged() = behaviour {
        lateinit var asked: Seen<Layer.Joined>
        given {
            val link = joinedTo30thWithSkiTripInvite()
            step("the switch asked", act = { openLink(link) }, until = {
                asked = screen<Layer.Joined> { it.asksToSwitchTo(SKI_TRIP) }
            })
        }
        val joined = asked.value

        whenever { tap(UiIntent.CancelSwitch) }
        then {
            screen<Layer.Joined> { it.pendingSwitch == null && it.membership == joined.membership }
            stays()
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Opening another event's invite asks before switching",
        scenario = "Backing out after the leave leaves no event",
    )
    fun cancelling_the_new_join_after_a_confirmed_switch_leaves_the_device_in_no_event() = behaviour {
        given {
            val link = joinedTo30thWithSkiTripInvite()
            step("the switch asked", act = { openLink(link) }, until = {
                screen<Layer.Joined> { it.asksToSwitchTo(SKI_TRIP) }
            })
            step("the switch confirmed", act = { tap(UiIntent.ConfirmSwitch) }, until = {
                screen<Layer.JoiningEvent> { (it.phase as? JoinPhase.Detailed)?.eventName == SKI_TRIP }
            })
        }

        whenever { tap(UiIntent.CancelJoin) }
        then {
            screen<Layer.CreateEvent>()
            never<WorldEvent.BackendReceived> { it.operation == JOIN }
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Opening another event's invite asks before switching",
        scenario = "A switch to a vanished event does not leave",
    )
    fun an_invite_to_an_event_that_no_longer_exists_is_reported_and_leaves_nothing() = behaviour {
        val link = given {
            val vanished = registeredEvent(name = SKI_TRIP)
            val link = inviteFor(vanished)
            joinedEvent(name = ANAS_30TH)
            step("the invited event swept", act = { backend.sweep(vanished) }, until = {})
            link
        }

        whenever { openLink(link) }
        then {
            screen<Layer.Joined> { it.membership.name == ANAS_30TH && it.pendingSwitch?.phase == JoinPhase.NotFound }
            stays()
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Opening another event's invite asks before switching",
        scenario = "A switch to a vanished event does not leave",
    )
    fun a_finished_event_is_not_left_for_an_invite_to_an_event_that_no_longer_exists() = behaviour {
        // The joined event would be left without a question — but only for an invite that can be joined.
        val link = given { endedAndReceived(othersUploading = false, inviteSwept = true) }

        whenever { openLink(link) }
        then {
            screen<Layer.Joined> { it.membership.name == ANAS_30TH && it.pendingSwitch?.phase == JoinPhase.NotFound }
            stays()
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Opening another event's invite asks before switching",
        scenario = "A member still sharing keeps the question",
    )
    fun an_ended_event_with_a_member_still_sharing_keeps_the_question() = behaviour {
        val link = given {
            val link = endedAndReceived(othersUploading = true)
            link
        }

        whenever { openLink(link) }
        then {
            screen<Layer.Joined> { it.asksToSwitchTo(SKI_TRIP) }
            stays()
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Opening another event's invite asks before switching",
        scenario = "Offline keeps the question",
    )
    fun an_ended_finished_event_the_phone_cannot_ask_about_keeps_the_question() = behaviour {
        val link = given {
            val link = endedAndReceived(othersUploading = false)
            // The invite's own details load first, and do; the one read the quiet switch then makes — the joined
            // event's — never reaches the service.
            step("the service unreachable for the read after the next", act = {
                backend.fail(GET_EVENT, BackendFault.UNREACHABLE, times = 1, after = 1)
            }, until = {})
            link
        }

        whenever { openLink(link) }
        then {
            screen<Layer.Joined> { it.asksToSwitchTo(SKI_TRIP) }
            stays()
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Opening another event's invite asks before switching",
        scenario = "A finished event is switched away from without asking",
    )
    fun a_finished_event_is_left_for_the_invite_without_a_question() = behaviour {
        val link = given { endedAndReceived(othersUploading = false) }

        whenever { openLink(link) }
        then {
            screen<Layer.JoiningEvent> { (it.phase as? JoinPhase.Detailed)?.eventName == SKI_TRIP }
            event<WorldEvent.BackendReceived> { it.operation == LEAVE }
            neverScreen<Layer.Joined> { it.pendingSwitch?.phase is JoinPhase.Detailed }
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Opening another event's invite asks before switching",
        scenario = "A deleted event is switched away from without asking",
    )
    fun a_deleted_event_is_left_for_the_invite_without_a_question() = behaviour {
        val link = given { deleted30thWithSkiTripInvite() }

        whenever { openLink(link) }
        then {
            screen<Layer.JoiningEvent> { (it.phase as? JoinPhase.Detailed)?.eventName == SKI_TRIP }
            neverScreen<Layer.Joined> { it.pendingSwitch?.phase is JoinPhase.Detailed }
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Opening another event's invite asks before switching",
        scenario = "Backing out after a switch without asking leaves no event",
    )
    fun cancelling_the_join_after_a_quiet_switch_leaves_the_device_in_no_event() = behaviour {
        given {
            val link = deleted30thWithSkiTripInvite()
            step("the invite opened", act = { openLink(link) }, until = {
                screen<Layer.JoiningEvent> { (it.phase as? JoinPhase.Detailed)?.eventName == SKI_TRIP }
            })
        }

        whenever { tap(UiIntent.CancelJoin) }
        then {
            screen<Layer.CreateEvent>()
            neverScreen<Layer.Joined>()
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** "Ski Trip" exists, this device is a member of "Ana's 30th", and holds Ski Trip's invite. Answers the invite. */
    private suspend fun Given.joinedTo30thWithSkiTripInvite(): String {
        val next = registeredEvent(name = SKI_TRIP)
        val link = inviteFor(next)
        joinedEvent(name = ANAS_30TH)
        return link
    }

    /** As [joinedTo30thWithSkiTripInvite], and Ana's 30th's photos deleted. */
    private suspend fun Given.deleted30thWithSkiTripInvite(): String {
        val next = registeredEvent(name = SKI_TRIP)
        val link = inviteFor(next)
        val current = joinedEvent(name = ANAS_30TH)
        deletedOnBackend(current)
        return link
    }

    /**
     * "Ana's 30th" has ended: this device shared everything it shares and received [DEV_F]'s photo; [DEV_F] settled its
     * share — with, when [othersUploading], a second photo declared and still on its phone. Answers Ski Trip's invite.
     */
    private suspend fun Given.endedAndReceived(othersUploading: Boolean, inviteSwept: Boolean = false): String {
        val next = registeredEvent(name = SKI_TRIP)
        val link = inviteFor(next)
        if (inviteSwept) step("the invited event swept", act = { backend.sweep(next) }, until = {})
        extensionUploadsOnly()
        joinedEvent(name = ANAS_30TH, startsAt = SHORT_START, endsAt = SHORT_END)
        received(DEV_F, "FQ", final = true)
        if (othersUploading) {
            step("$DEV_F still uploading a photo", act = {
                publicBackend.foreignDevice(DEV_F, listOf("FQ", "FR"), final = true, uploading = listOf("FR"))
            }, until = {})
        }
        laterInThePocket(AFTER_THE_END)
        step("this device's share settled", act = { cycle() }, until = { settled { true } })
        inFront()
        return link
    }

    /** The joined screen asks whether to switch to [name], its details loaded. */
    private fun Layer.Joined.asksToSwitchTo(name: String): Boolean =
        (pendingSwitch?.phase as? JoinPhase.Detailed)?.let {
            it.eventName == name && it.step == JoinPhase.Detailed.Step.Ready
        } == true

    /** The member is still in the event they were in: nothing reached the backend's leave, and no other screen showed. */
    private fun Expectations.stays() {
        never<WorldEvent.BackendReceived> { it.operation == LEAVE }
        neverScreen<Layer.JoiningEvent>()
        neverScreen<Layer.CreateEvent>()
    }

    private companion object {
        const val ANAS_30TH = "Ana's 30th"
        const val SKI_TRIP = "Ski Trip"
        const val LEAVE = "leaveEvent"
        const val JOIN = "joinEvent"
        const val GET_EVENT = "getEvent"

        /** A short range around the start of every behaviour's clock ([app.snapsync.control.behaviour.NOW], 1 June noon). */
        const val SHORT_START = "2026-05-25T00:00:00"
        const val SHORT_END = "2026-06-02T00:00:00"

        /** Past [SHORT_END], and its open-until date with it; long before its photos may be deleted. */
        const val AFTER_THE_END = "2026-06-04T12:00:00Z"
    }
}
