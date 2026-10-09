package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.model.JoinPhase
import app.snapsync.model.LINK_ORIGIN
import app.snapsync.model.Layer
import app.snapsync.model.ScreenMessage
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
        requirement = "The invite link format stays openable forever",
        scenario = "A path-form link opens the join screen",
    )
    fun a_path_form_link_opens_the_join_screen_for_its_event() = rigTest {
        // The bare path form names a PLAIN event; an encrypted one's carries its key (`EncryptedEventIntegrationTest`).
        device("encrypt-new-events", "on" to "false")
        val event = registerEvent(name = "Anna's 40th")

        openLink("$LINK_ORIGIN/join/$event")

        val gate = awaitState { ((it.ui.layer as? Layer.JoiningEvent)?.phase as? JoinPhase.Detailed) != null }
            .ui.layer as Layer.JoiningEvent
        assertEquals(event, gate.eventId)
        assertEquals("Anna's 40th", (gate.phase as JoinPhase.Detailed).event.name)
    }

    @Test
    @Verifies(spec = "invite-link", requirement = "A damaged invite is reported and changes nothing")
    fun a_malformed_path_form_link_is_reported_and_changes_nothing() = rigTest {
        openLink("$LINK_ORIGIN/join/not-an-event")

        val create = awaitState { (it.ui.layer as? Layer.CreateEvent)?.error != null }.ui.layer as Layer.CreateEvent
        assertEquals(ScreenMessage.INVALID_LINK, create.error)
        assertEquals(null, state().joined, "a damaged invite joins nothing")
    }
}
