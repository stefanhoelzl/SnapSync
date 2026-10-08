package app.snapsync.integration

import app.snapsync.model.JoinPhase
import app.snapsync.model.LINK_ORIGIN
import app.snapsync.model.Layer
import app.snapsync.model.SyncHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An encrypted event, as a member meets it (the encrypted file format, `docs/architecture.md`) — every event a build
 * creates, unless a rig build asks for a plain one by `device/encrypt-new-events?on=false`. Its invite carries its key;
 * an invite without it — a link cut short in sharing — opens the incomplete-invite wall and joins nothing.
 */
class EncryptedEventIntegrationTest {

    private suspend fun Rig.creatingPlain() = device("encrypt-new-events", "on" to "false")

    @Test
    fun a_shipped_build_creates_an_encrypted_event_whose_invite_carries_its_key() = rigTest {
        val event = createAndJoin()
        val invite = awaitState { it.joined?.inviteUrl?.contains("#k=") == true }.joined!!.inviteUrl!!
        assertTrue(invite.startsWith("$LINK_ORIGIN/join/$event#k="), invite)
        assertEquals(43, invite.substringAfter("#k=").length, "a 32-byte key, base64url")
    }

    @Test
    fun a_plain_event_keeps_the_fragment_form_invite() = rigTest {
        creatingPlain()
        createAndJoin()
        val invite = awaitState { it.joined != null }.joined!!.inviteUrl!!
        assertTrue(invite.startsWith("$LINK_ORIGIN/join#v=3&d="), invite)
    }

    @Test
    fun an_invite_without_the_key_never_joins_an_encrypted_event() = rigTest {
        val event = createAndJoin()
        val invite = awaitState { it.joined?.inviteUrl?.contains("#k=") == true }.joined!!.inviteUrl!!
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

    @Test
    fun a_lost_key_stops_both_directions_and_the_reopened_invite_resumes_them() = rigTest {
        val event = createAndJoin()
        val invite = awaitState { it.joined?.inviteUrl?.contains("#k=") == true }.joined!!.inviteUrl!!
        val settingsBefore = state().ready

        // The phone was restored onto a new device: the membership came back, the key did not.
        device("event-key/lose")
        foreground()
        awaitState { it.joined?.health == SyncHealth.KeyLost }
        assertNull(state().joined?.inviteUrl, "no invite is offered without its key")

        // Neither uploader takes the new photo, and the other member's photo is not fetched.
        addPhoto("OWN-1")
        foreignDevice("DEV-F", "FQ")
        foreground()
        assertEquals("skipped", cycle(), "the extension's cycle is withheld")
        assertEquals(0, jobs().created, "the extension creates no upload")
        assertEquals(0, appUploads().created, "the app's uploader creates no upload")
        assertEquals(emptySet(), objects(), "nothing of this device reached the event")
        assertEquals(0, state().download.inFlight, "no download starts")
        assertEquals(SyncHealth.KeyLost, state().joined?.health)

        // An invite without its key gives nothing back.
        openLink("$LINK_ORIGIN/join/$event")
        foreground()
        assertEquals(SyncHealth.KeyLost, state().joined?.health)

        // The whole invite gives the key back in place: no join screen, the same settings, both directions resume.
        openLink(invite)
        awaitState { it.joined != null && it.joined?.health != SyncHealth.KeyLost }
        assertEquals(settingsBefore, state().ready, "the membership's settings are unchanged")
        assertEquals(null, state().joined?.pendingSwitch, "no join screen")
        assertEquals(
            invite,
            awaitState { it.joined?.inviteUrl != null }.joined!!.inviteUrl,
            "the whole invite is offered again",
        )
        awaitAppUploads(1)
        downloadAll()
        assertTrue(gallery().census.total >= 2, "the other member's photo arrived beside the own one")
    }
}
