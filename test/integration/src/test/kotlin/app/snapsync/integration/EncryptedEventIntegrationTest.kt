package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.format.LINK_ORIGIN
import app.snapsync.levers.KeychainItem
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An encrypted event, as a member meets it (the encrypted file format, `docs/architecture.md`) — every event a rig
 * build creates unless it asks for a plain one by `dev-controls.encryptNewEvents(false)` (a shipped build creates plain
 * events until encrypted creation is re-enabled, `PENDING_CLEANUPS.md`). Its invite carries its key;
 * an invite without it — a link cut short in sharing — opens the incomplete-invite wall and joins nothing.
 */
class EncryptedEventIntegrationTest {

    private suspend fun Given.creatingPlain() =
        step("events created plain", act = { devControls.encryptNewEvents(false) }, until = {})

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Every invite form opens in every future version",
        scenario = "An encrypted event's invite carries its key",
    )
    fun an_encrypted_event_s_invite_carries_its_key() = behaviour {
        val event = given { createdEvent() }

        lateinit var joined: Seen<Layer.Joined>
        whenever { confirmJoin() }
        then { joined = screen<Layer.Joined> { it.inviteUrl?.contains("#k=") == true } }
        val invite = joined.value.inviteUrl!!
        assertTrue(invite.startsWith("$LINK_ORIGIN/join/$event#k="), invite)
        assertEquals(43, invite.substringAfter("#k=").length, "a 32-byte key, base64url")
    }

    @Test
    @Verifies(spec = "invite-link", requirement = "Every invite form opens in every future version")
    fun a_plain_event_keeps_the_fragment_form_invite() = behaviour {
        given {
            creatingPlain()
            createdEvent()
        }

        lateinit var joined: Seen<Layer.Joined>
        whenever { confirmJoin() }
        then { joined = screen<Layer.Joined> { it.inviteUrl != null } }
        val invite = joined.value.inviteUrl!!
        assertTrue(invite.startsWith("$LINK_ORIGIN/join#v=3&d="), invite)
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Whoever holds the invite can join and see everything",
        scenario = "A forwarded invite admits a stranger",
    )
    fun a_forwarded_invite_admits_a_stranger_who_shares_and_receives() = behaviour {
        val (event, invite) = given {
            val event = registeredEvent()
            step(
                "$DEV_F shares FQ",
                act = { publicBackend.foreignDevice(DEV_F, listOf("FQ"), event = event) },
                until = { settled { true } },
            )
            event to inviteFor(event)
        }
        // With nothing but the forwarded invite — no account, no approval — the stranger joins.
        whenever { openLink(invite) }
        then { screen<Layer.JoiningEvent> { it.eventId == event && it.phase is JoinPhase.Detailed } }
        whenever { confirmJoin() }
        then {
            screen<Layer.Joined> { it.membership.eventId == event }
            event<WorldEvent.MemberJoined> { it.event == event }
            event<WorldEvent.DownloadStarted>()
            settled { true }
        }

        // They contribute, and receive the other member's photo.
        whenever {
            takePhoto("OWN-1")
            downloadSession.finish()
            uploadAll()
        }
        then {
            event<WorldEvent.BytesStored> { it.event == event && it.device != DEV_F && it.asset == "OWN-1" }
            event<WorldEvent.LibraryAssetCreated> { it.asset == "imported-$DEV_F-FQ" }
        }
    }

    @Test
    @Verifies(spec = "invite-link", requirement = "An incomplete invite never joins an encrypted event")
    @Verifies(
        spec = "invite-link",
        requirement = "An incomplete invite never joins an encrypted event",
        scenario = "An invite without its key",
    )
    @Verifies(
        spec = "invite-link",
        requirement = "An incomplete invite never joins an encrypted event",
        scenario = "The whole invite still opens the event",
    )
    fun an_invite_without_the_key_never_joins_an_encrypted_event() = behaviour {
        val (event, invite) = given {
            val event = createdEvent()
            val invite = joinedWithItsInvite().inviteUrl!!
            step("left", act = { tap(UiIntent.LeaveEvent) }, until = {
                screen<Layer.CreateEvent>()
                settled { true }
            })
            event to invite
        }

        whenever { openLink("$LINK_ORIGIN/join/$event") }
        then {
            screen<Layer.JoiningEvent> { it.phase == JoinPhase.WrongLink }
            never<WorldEvent.MemberJoined>()
            settled { true }
        }
        whenever { tap(UiIntent.CancelJoin) }
        then { screen<Layer.CreateEvent>() }

        // The whole invite still opens it.
        whenever { openLink(invite) }
        then { screen<Layer.JoiningEvent> { it.eventId == event && it.phase is JoinPhase.Detailed } }
        whenever { confirmJoin() }
        then {
            screen<Layer.Joined> { it.membership.eventId == event }
            event<WorldEvent.MemberJoined> { it.event == event }
        }
    }

    @Test
    @Verifies(spec = "invite-link", requirement = "An incomplete invite never joins an encrypted event")
    fun a_wrong_key_never_opens_an_encrypted_event() = behaviour {
        val event = given { registeredEvent() }

        whenever { openLink("$LINK_ORIGIN/join/$event#k=" + "A".repeat(42) + "E") }
        then {
            screen<Layer.JoiningEvent> { it.phase == JoinPhase.WrongLink }
            never<WorldEvent.MemberJoined>() // no enrolment
            settled { true }
        }
    }

    @Test
    @Verifies(spec = "invite-link", requirement = "An incomplete invite never joins an encrypted event")
    fun a_key_never_opens_a_plain_event() = behaviour {
        val plain = given {
            creatingPlain()
            val event = registeredEvent()
            // Plain, as only a plain event is: its keyless link opens the join screen.
            step(
                "its keyless link opens it",
                act = { openLink("$LINK_ORIGIN/join/$event") },
                until = { screen<Layer.JoiningEvent> { it.eventId == event && it.phase is JoinPhase.Detailed } },
            )
            step("the join screen left", act = { tap(UiIntent.CancelJoin) }, until = { screen<Layer.CreateEvent>() })
            event
        }

        whenever { openLink("$LINK_ORIGIN/join/$plain#k=" + "A".repeat(42) + "E") }
        then {
            screen<Layer.JoiningEvent> { it.eventId == plain && it.phase == JoinPhase.WrongLink }
            never<WorldEvent.MemberJoined>() // no enrolment
            settled { true }
        }
    }

    @Test
    @Verifies(spec = "sync-status", requirement = "A device that lost the event's key asks for its invite")
    @Verifies(spec = "invite-link", requirement = "Reopening the current event's invite changes nothing")
    @Verifies(
        spec = "invite-link",
        requirement = "Only the whole invite opens an event's photos",
        scenario = "A phone that cannot read the key uploads nothing",
    )
    @Verifies(
        spec = "invite-link",
        requirement = "The joined screen offers the invite until the event's photos are deleted",
        scenario = "A phone that lost the event's key offers no invite",
    )
    @Verifies(
        spec = "invite-link",
        requirement = "The joined screen offers the invite until the event's photos are deleted",
        scenario = "The invite returns with the key",
    )
    @Verifies(
        spec = "sync-status",
        requirement = "A device that lost the event's key asks for its invite",
        scenario = "The invite ends it",
    )
    fun a_lost_key_stops_both_directions_and_the_reopened_invite_resumes_them() = behaviour {
        val (event, before) = given { createdEvent() to joinedWithItsInvite() }
        val invite = before.inviteUrl!!

        // The phone was restored onto a new device: the membership came back, the key did not.
        whenever {
            keychain.forget(KeychainItem.EVENT_KEY)
            os.foreground()
        }
        then {
            screen<Layer.Joined> { it.health == SyncHealth.KeyLost && it.inviteUrl == null }
            // no invite is offered without its key
            neverScreen<Layer.Joined> { it.health == SyncHealth.KeyLost && it.inviteUrl != null }
            settled { true }
        }

        // Neither uploader takes the new photo, and the other member's photo is not fetched.
        lateinit var answer: String
        whenever {
            takePhoto("OWN-1")
            publicBackend.foreignDevice(DEV_F, listOf("FQ"))
            os.foreground()
            answer = cycle()
        }
        then {
            never<WorldEvent.UploadJobCreated>() // the extension creates no upload
            never<WorldEvent.SessionTransferCreated>() // the app's uploader creates no upload
            never<WorldEvent.BytesStored> { it.device != DEV_F } // nothing of this device reached the event
            never<WorldEvent.DownloadStarted>() // no download starts
            never<WorldEvent.UiChanged> { (it.ui.layer as? Layer.Joined)?.health != SyncHealth.KeyLost }
        }
        assertEquals("completed", answer, "the extension's cycle is withheld, and the system rests")

        // An invite without its key gives nothing back.
        whenever {
            openLink("$LINK_ORIGIN/join/$event")
            os.foreground()
        }
        then { never<WorldEvent.UiChanged> { (it.ui.layer as? Layer.Joined)?.health != SyncHealth.KeyLost } }

        // Nor does one carrying another key.
        whenever {
            openLink("$LINK_ORIGIN/join/$event#k=" + "A".repeat(42) + "E")
            os.foreground()
        }
        then {
            never<WorldEvent.UiChanged> { (it.ui.layer as? Layer.Joined)?.health != SyncHealth.KeyLost }
            settled { true }
        }

        // The whole invite gives the key back in place: no join screen, the same settings, both directions resume.
        lateinit var resumed: Seen<Layer.Joined>
        whenever { openLink(invite) }
        then {
            resumed = screen<Layer.Joined> { it.health != SyncHealth.KeyLost && it.inviteUrl == invite }
            neverScreen<Layer.JoiningEvent>() // no join screen
            neverScreen<Layer.Joined> { it.pendingSwitch != null }
            event<WorldEvent.SessionTransferCreated>()
            event<WorldEvent.DownloadStarted>()
            settled { true }
        }
        assertEquals(before.membership, resumed.value.membership, "the membership's settings are unchanged")

        whenever { downloadSession.finish() }
        then {
            // the other member's photo arrived beside the own one
            event<WorldEvent.LibraryAssetCreated> { it.asset == "imported-$DEV_F-FQ" }
            settled { it.state.libraryPhotos.orEmpty().size >= 2 }
        }
    }

    /** Joined through the open gate; answers the joined screen once it offers the whole invite, its key included. */
    private suspend fun Given.joinedWithItsInvite(): Layer.Joined {
        lateinit var joined: Seen<Layer.Joined>
        step("joined", act = { confirmJoin() }, until = {
            joined = screen<Layer.Joined> { it.inviteUrl?.contains("#k=") == true }
            settled { true }
        })
        return joined.value
    }
}
