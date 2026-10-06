package app.snapsync.integration

import app.snapsync.model.JoinPhase
import app.snapsync.model.LINK_ORIGIN
import app.snapsync.model.Layer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An encrypted event, as a member meets it (the encrypted file format, `docs/architecture.md`) — reachable only on a rig
 * build until encryption is enabled, by `device/encrypt-new-events?on=true`. Its invite carries its key; an invite
 * without it — a link cut short in sharing — opens the incomplete-invite wall and joins nothing.
 */
class EncryptedEventIntegrationTest {

    private suspend fun Rig.encrypting() = device("encrypt-new-events", "on" to "true")

    @Test
    fun the_creator_of_an_encrypted_event_shares_an_invite_carrying_its_key() = rigTest {
        encrypting()
        val event = createAndJoin()
        val invite = awaitState { it.joined?.inviteUrl?.contains("#k=") == true }.joined!!.inviteUrl
        assertTrue(invite.startsWith("$LINK_ORIGIN/join/$event#k="), invite)
        assertEquals(43, invite.substringAfter("#k=").length, "a 32-byte key, base64url")
    }

    @Test
    fun a_shipped_build_creates_plain_events_whose_invite_is_unchanged() = rigTest {
        createAndJoin()
        val invite = awaitState { it.joined != null }.joined!!.inviteUrl
        assertTrue(invite.startsWith("$LINK_ORIGIN/join#v=3&d="), invite)
    }

    @Test
    fun an_invite_without_the_key_never_joins_an_encrypted_event() = rigTest {
        encrypting()
        val event = createAndJoin()
        val invite = awaitState { it.joined?.inviteUrl?.contains("#k=") == true }.joined!!.inviteUrl
        leave()

        openLink("$LINK_ORIGIN/join/$event")
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.phase == JoinPhase.WrongLink }
        user("cancelJoin")
        awaitState { it.ui.layer is Layer.CreateEvent }

        // The whole invite still opens it.
        openLink(invite)
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.phase is JoinPhase.Detailed }
        assertEquals(event, join())
    }

    @Test
    fun a_key_never_opens_a_plain_event() = rigTest {
        val plain = registerEvent()
        openLink("$LINK_ORIGIN/join/$plain#k=" + "A".repeat(42) + "E")
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.phase == JoinPhase.WrongLink }
        assertNull(manifest(plain), "no enrolment")
    }
}
