package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.format.LINK_ORIGIN
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test

/**
 * **Every invite form an installed build shares opens** (capability `invite-link`): a plain event's fragment form as
 * well as the path form, and a link damaged in sharing is told as what it is — a damaged invite when it names no event,
 * an incomplete one when only its key cannot be read — and joins nothing.
 */
class InviteFormsIntegrationTest {

    @Test
    @Verifies(spec = "invite-link", requirement = "Every invite form opens in every future version")
    fun a_plain_events_fragment_form_invite_opens_the_join_screen() = behaviour {
        val link = given {
            step("events are created plain", act = { devControls.encryptNewEvents(false) }, until = {})
            inviteFor(registeredEvent(name = "Anna's 40th"))
        }

        whenever { openLink(link) }
        then { screen<Layer.JoiningEvent> { (it.phase as? JoinPhase.Detailed)?.eventName == "Anna's 40th" } }
    }

    @Test
    @Verifies(spec = "invite-link", requirement = "Every invite form opens in every future version")
    fun a_path_form_invite_with_no_key_field_opens_the_join_screen() = behaviour {
        val event = given {
            step("events are created plain", act = { devControls.encryptNewEvents(false) }, until = {})
            registeredEvent(name = "Anna's 40th")
        }

        whenever { openLink("$LINK_ORIGIN/join/$event/#from=qr") }
        then { screen<Layer.JoiningEvent> { (it.phase as? JoinPhase.Detailed)?.eventName == "Anna's 40th" } }
    }

    @Test
    @Verifies(spec = "invite-link", requirement = "A damaged invite is reported and changes nothing")
    fun a_damaged_fragment_form_invite_is_reported_and_joins_nothing() = behaviour {
        whenever { openLink("$LINK_ORIGIN/join#v=3&d=not-base64!") }
        then {
            screen<Layer.CreateEvent> { it.error == ScreenMessage.INVALID_LINK }
            neverScreen<Layer.JoiningEvent>()
        }
    }

    @Test
    @Verifies(spec = "invite-link", requirement = "An incomplete invite never joins an encrypted event")
    fun an_invite_whose_key_part_is_garbled_is_incomplete_and_joins_nothing() = behaviour {
        val event = given { registeredEvent() }

        for (fragment in listOf("#garbled", "#k=@@@")) {
            whenever { openLink("$LINK_ORIGIN/join/$event$fragment") }
            then {
                screen<Layer.JoiningEvent> { it.eventId == event && it.phase == JoinPhase.WrongLink }
                neverScreen<Layer.Joined>()
            }
            whenever { tap(UiIntent.CancelJoin) }
            then { screen<Layer.CreateEvent>() }
        }
    }
}
