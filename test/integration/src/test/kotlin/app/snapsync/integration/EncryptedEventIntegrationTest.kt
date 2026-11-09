@file:OptIn(ShowsInvite::class)

package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.format.LINK_ORIGIN
import app.snapsync.levers.KeychainItem
import app.snapsync.levers.StoreFault
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.ShowsInvite
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
        then { joined = screen<Layer.Joined> { it.inviteUrl?.raw?.contains("#k=") == true } }
        val invite = joined.value.inviteUrl!!.raw
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
        val invite = joined.value.inviteUrl!!.raw
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
            val invite = joinedWithItsInvite().inviteUrl!!.raw
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
        val invite = before.inviteUrl!!.raw

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
            resumed = screen<Layer.Joined> { it.health != SyncHealth.KeyLost && it.inviteUrl?.raw == invite }
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

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "The service keeps an encrypted event's name only encrypted",
        scenario = "The stored event does not reveal its name",
    )
    @Verifies(
        spec = "invite-link",
        requirement = "The service keeps an encrypted event's name only encrypted",
        scenario = "A renamed event's new name is stored encrypted",
    )
    @Verifies(
        spec = "invite-link",
        requirement = "Every invite form opens in every future version",
        scenario = "A stale preview name changes nothing in the app",
    )
    fun an_encrypted_event_s_name_is_served_only_sealed_and_a_stale_shared_invite_opens_under_its_current_name() =
        behaviour {
            val event = given { createdEvent(name = "Ana's 3oth") }
            whenever { confirmJoin() }
            then { screen<Layer.Joined> { it.membership.name == "Ana's 3oth" && it.inviteUrl != null } }
            // The backend serves the event — its key id, its dates — but no readable name (`changes/seal-event-name`).
            var served: String? = "unread"
            whenever { served = publicBackend.event(event).name }
            assertEquals(null, served, "the backend serves no readable name")

            // The invite shared before the rename carries the old name for previews.
            lateinit var shared: String
            whenever { tap(UiIntent.ShareInvite) }
            then {
                event<WorldEvent.Shared> {
                    shared = it.text
                    it.text.contains("?n=")
                }
            }

            whenever { rename("Ana's 30th") }
            then {
                screen<Layer.Joined> { it.membership.name == "Ana's 30th" }
                event<WorldEvent.EventRenamed> { it.event == event && it.name == "Ana's 30th" }
                settled { true }
            }
            served = "unread"
            whenever { served = publicBackend.event(event).name }
            assertEquals(null, served, "and the new one is sealed too")

            // Another device opening that stale invite meets the event under the name its members gave it now.
            whenever { tap(UiIntent.LeaveEvent) }
            then { screen<Layer.CreateEvent>() }
            whenever { openLink(shared) }
            then {
                screen<Layer.JoiningEvent> {
                    it.eventId == event && (it.phase as? JoinPhase.Detailed)?.eventName == "Ana's 30th"
                }
                neverScreen<Layer.JoiningEvent> { it.phase == JoinPhase.WrongLink }
            }
        }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Every invite form opens in every future version",
        scenario = "An invite without a preview name still opens",
    )
    fun a_whole_invite_without_its_preview_name_opens_the_event_named() = behaviour {
        val (event, invite) = given {
            val event = createdEvent(name = "Ski Trip")
            val invite = joinedWithItsInvite().inviteUrl!!.raw
            step("left", act = { tap(UiIntent.LeaveEvent) }, until = { screen<Layer.CreateEvent>() })
            event to invite
        }
        // The QR code's invite: the key, and no preview name.
        assertTrue(!invite.contains("?"), invite)
        whenever { openLink(invite) }
        then {
            screen<Layer.JoiningEvent> {
                it.eventId == event && (it.phase as? JoinPhase.Detailed)?.eventName == "Ski Trip"
            }
        }
    }

    @Test
    @Verifies(spec = "join-event", requirement = "The join screen verifies the event before offering to join")
    fun a_whole_invite_whose_sealed_name_does_not_open_is_a_load_that_failed_never_an_unnamed_join() = behaviour {
        val (event, invite) = given {
            val event = createdEvent()
            val invite = joinedWithItsInvite().inviteUrl!!.raw
            step("left", act = { tap(UiIntent.LeaveEvent) }, until = { screen<Layer.CreateEvent>() })
            event to invite
        }
        // A record the event's key does not open — the service misbehaving, or a member sealing under another key.
        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("FQ"), event = event)
            publicBackend.foreignDeviceSealedRename(DEV_F, "AQ" + "A".repeat(40), event)
        }
        whenever { openLink(invite) }
        then {
            screen<Layer.JoiningEvent> { it.eventId == event && it.phase == JoinPhase.LoadFailed }
            neverScreen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed }
            never<WorldEvent.MemberJoined> { it.device != DEV_F }
            settled { true }
        }
    }

    @Test
    @Verifies(spec = "manage-membership", requirement = "Leaving asks first, then is instant and works offline")
    fun a_leave_whose_key_the_keychain_will_not_delete_still_leaves_and_the_next_event_joins() = behaviour {
        given { joinedEvent() }

        whenever {
            keychain.fail(KeychainItem.EVENT_KEY, StoreFault.WRITE)
            tap(UiIntent.LeaveEvent)
        }
        then {
            event<WorldEvent.KeychainFaulted> { it.item == KeychainItem.EVENT_KEY && it.kind == StoreFault.WRITE }
            event<WorldEvent.MemberLeft>()
            screen<Layer.CreateEvent>()
            settled { true }
        }

        // The key left behind keeps nothing from the next event: it is joined with a key of its own.
        given { joinedEvent(name = "The next one") }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "The service keeps an encrypted event's photo names only encrypted",
        scenario = "A received photo keeps its sender's name",
    )
    fun an_encrypted_event_s_photo_arrives_under_its_sender_s_name_which_the_service_held_only_sealed() = behaviour {
        given {
            val encrypted = createdEvent()
            joinedWithItsInvite()
            // Shared as any member's build shares into an encrypted event: its name sealed for that one photo.
            step("FQ, named IMG_4471.HEIC, on its way", act = {
                publicBackend.foreignDevice(DEV_F, listOf("FQ"), event = encrypted, filename = "IMG_4471.HEIC")
                os.foreground()
            }, until = {
                event<WorldEvent.DownloadStarted>()
                settled { true }
            })
        }

        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { created ->
                created.names.size == 1 && Regex("""IMG_4471\.snapsync-[a-z2-7]{10}\.HEIC""").matches(created.names.single())
            }
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "The service keeps an encrypted event's photo names only encrypted",
        scenario = "A photo whose name does not open",
    )
    fun a_photo_whose_sealed_name_does_not_open_never_arrives_and_the_others_still_do() = behaviour {
        given {
            val encrypted = createdEvent()
            joinedWithItsInvite()
            step("FQ named under no key the event opens, FR named as any build names it", act = {
                publicBackend.foreignDevice(DEV_F, listOf("FQ"), event = encrypted, sealedFilename = UNOPENABLE_NAME)
                publicBackend.foreignDevice(OTHER_MEMBER, listOf("FR"), event = encrypted)
                os.foreground()
            }, until = {
                event<WorldEvent.DownloadStarted>()
                settled { true }
            })
        }

        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == "imported-$OTHER_MEMBER-FR" }
            settled { true }
            never<WorldEvent.LibraryAssetCreated> { it.asset == "imported-$DEV_F-FQ" }
        }
    }

    /** Joined through the open gate; answers the joined screen once it offers the whole invite, its key included. */
    private suspend fun Given.joinedWithItsInvite(): Layer.Joined {
        lateinit var joined: Seen<Layer.Joined>
        step("joined", act = { confirmJoin() }, until = {
            joined = screen<Layer.Joined> { it.inviteUrl?.raw?.contains("#k=") == true }
            settled { true }
        })
        return joined.value
    }
}

/** A sealed name's shape that opens under no key: what a member sealing under another key, or a tampering service, uploads. */
private val UNOPENABLE_NAME = "AQ" + "A".repeat(40)

/** A second fellow member, who shares into the event as any build does. */
private const val OTHER_MEMBER = "def0f000-0000-4000-8000-0000000000e1"
