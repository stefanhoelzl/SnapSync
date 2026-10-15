package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.format.LINK_ORIGIN
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.ScreenMessage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The invite's path form (`changes/server-rendered-event-page`): `<origin>/join/<eventId>`, delivered as the
 * operating system delivers a
 * scanned or tapped link, opens the same join screen the fragment form does, and a malformed one is reported as a
 * damaged invite — over the real stack, against the backend mock.
 */
class PathFormLinkIntegrationTest {

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Every invite form opens in every future version",
        scenario = "A path-form link opens the join screen",
    )
    fun a_path_form_link_opens_the_join_screen_for_its_event() = behaviour {
        val event = given {
            // The bare path form names a PLAIN event; an encrypted one's carries its key (`EncryptedEventIntegrationTest`).
            step("plain events", act = { devControls.encryptNewEvents(false) }, until = {})
            registeredEvent(name = "Anna's 40th")
        }

        lateinit var gate: Seen<Layer.JoiningEvent>
        whenever { openLink("$LINK_ORIGIN/join/$event") }
        then { gate = screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed } }
        assertEquals(event, gate.value.eventId)
        assertEquals("Anna's 40th", (gate.value.phase as JoinPhase.Detailed).eventName)
    }

    @Test
    @Verifies(spec = "invite-link", requirement = "A damaged invite is reported and changes nothing")
    fun a_malformed_path_form_link_is_reported_and_changes_nothing() = behaviour {
        lateinit var create: Seen<Layer.CreateEvent>
        whenever { openLink("$LINK_ORIGIN/join/not-an-event") }
        then {
            create = screen<Layer.CreateEvent> { it.error != null }
            // A damaged invite joins nothing.
            neverScreen<Layer.Joined>()
            never<WorldEvent.MemberJoined>()
        }
        assertEquals(ScreenMessage.INVALID_LINK, create.value.error)
    }
}
