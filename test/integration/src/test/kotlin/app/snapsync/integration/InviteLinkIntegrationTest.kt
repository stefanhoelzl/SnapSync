package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.format.LINK_ORIGIN
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.JoinStage
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.UiIntent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * **The invite** (capability `invite-link`), over the real composed stack: the forms it is shared and opened in, what it
 * carries, how it brings the app to its join screen, how a damaged or incomplete one is told without touching anything,
 * and the joined screen offering it until the event's photos are deleted.
 */
class InviteLinkIntegrationTest {

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Every invite form opens in every future version",
        scenario = "A link made by an older version opens in a newer one",
    )
    fun a_fragment_form_link_with_an_older_builds_hints_opens_the_join_screen() = behaviour {
        val event = given {
            plainEvents()
            registeredEvent(name = "Anna's 40th")
        }

        whenever { openLink(fragmentForm("""{"eventId":"$event","direction":"both","saveToAlbum":false}""")) }
        then {
            screen<Layer.JoiningEvent> { it.eventId == event && (it.phase as? JoinPhase.Detailed)?.eventName == "Anna's 40th" }
            never<WorldEvent.MemberJoined>()
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Every invite form opens in every future version",
        scenario = "A link shared today opens in an app from before this version",
    )
    fun a_plain_events_shared_invite_is_the_fragment_form_older_apps_read() = behaviour {
        given {
            plainEvents()
            joinedEvent()
        }

        whenever { tap(UiIntent.ShareInvite) }
        then { event<WorldEvent.Shared> { it.text.startsWith("$LINK_ORIGIN/join#v=3&d=") } }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Every invite form opens in every future version",
        scenario = "The invite carries the event identifier only",
    )
    fun a_plain_events_invite_carries_its_identifier_and_nothing_else() = behaviour {
        val event = given {
            plainEvents()
            joinedEvent()
        }

        lateinit var shared: Seen<WorldEvent.Shared>
        whenever { tap(UiIntent.ShareInvite) }
        then { shared = event<WorldEvent.Shared>() }

        val payload = Json.parseToJsonElement(
            String(Base64.getUrlDecoder().decode(shared.value.text.substringAfter("&d=").substringBefore('&'))),
        ).jsonObject
        assertEquals(setOf("eventId"), payload.keys, "the payload names the event and nothing else")
        assertEquals(event, payload.getValue("eventId").toString().trim('"'))
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Every invite form opens in every future version",
        scenario = "The invite carries the event identifier only",
    )
    fun an_encrypted_events_invite_carries_its_identifier_and_its_key_only() = behaviour {
        val event = given { joinedEvent() }

        lateinit var shared: Seen<WorldEvent.Shared>
        whenever { tap(UiIntent.ShareInvite) }
        then { shared = event<WorldEvent.Shared>() }

        val prefix = "$LINK_ORIGIN/join/$event#k="
        val text = shared.value.text
        assertEquals(prefix, text.take(prefix.length), "the path names the event, the fragment its key")
        assertEquals(false, text.removePrefix(prefix).contains('&'), "the fragment holds the key alone")
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Only the whole invite opens an event's photos",
        scenario = "A bug report carries no key",
    )
    fun a_bug_report_from_an_encrypted_event_carries_no_key() = behaviour {
        lateinit var joined: Seen<Layer.Joined>
        given {
            createdEvent()
            step("joined", act = { confirmJoin() }, until = {
                joined = screen<Layer.Joined> { it.inviteUrl?.contains("#k=") == true }
                settled { true }
            })
        }

        lateinit var received: Seen<WorldEvent.DumpReceived>
        whenever { tap(UiIntent.SendDiagnostics("photos stopped arriving", "Joined")) }
        then { received = event<WorldEvent.DumpReceived>() }

        val key = joined.value.inviteUrl!!.substringAfter("#k=")
        val dump = received.value.dump
        val carried = listOf(dump.note, dump.appLog, dump.extensionLog) + dump.state.values + dump.ledger.values
        assertEquals(emptyList(), carried.filter { key in it }, "the key appears nowhere in the report")
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "An incomplete invite never joins an encrypted event",
        scenario = "A member is not moved by an incomplete invite",
    )
    fun an_incomplete_invite_leaves_a_member_where_they_are() = behaviour {
        lateinit var before: Seen<Layer.Joined>
        val other = given {
            val other = registeredEvent(name = "Anna's Wedding")
            joinedEvent()
            step("the joined screen read", act = { os.foreground() }, until = {
                before = screen<Layer.Joined>()
                settled { true }
            })
            other
        }

        whenever { openLink("$LINK_ORIGIN/join/$other") }
        then {
            screen<Layer.Joined> { it.pendingSwitch?.phase == JoinPhase.WrongLink }
            never<WorldEvent.MemberLeft>()
            neverScreen<Layer.Joined> { it.membership != before.value.membership }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "An invite opens the app on its join screen",
        scenario = "Scanning with the app closed opens the join screen",
    )
    fun an_invite_opened_on_a_launch_lands_on_its_join_screen() = behaviour {
        val event = given {
            registeredEvent().also {
                step("the app not running", act = { host.relaunch(scene = false) }, until = { settled { true } })
            }
        }

        whenever {
            openLink(inviteLink(event))
            os.foreground()
        }
        then { screen<Layer.JoiningEvent> { it.eventId == event && (it.phase as? JoinPhase.Detailed)?.step == READY } }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "An invite opens the app on its join screen",
        scenario = "Tapping an invite while the app is running opens the join screen",
    )
    fun an_invite_tapped_while_the_app_is_in_the_background_brings_its_join_screen() = behaviour {
        val event = given {
            registeredEvent().also {
                step("in the background", act = { os.background() }, until = { settled { true } })
            }
        }

        whenever {
            openLink(inviteLink(event))
            os.foreground()
        }
        then { screen<Layer.JoiningEvent> { it.eventId == event && (it.phase as? JoinPhase.Detailed)?.step == READY } }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "An invite opens the app on its join screen",
        scenario = "A link delivered twice opens one join screen",
    )
    fun the_same_invite_delivered_again_leaves_the_choices_made() = behaviour {
        val event = given {
            val event = registeredEvent()
            step("the invite opened", act = { openLink(inviteLink(event)) }, until = {
                screen<Layer.JoiningEvent> { (it.phase as? JoinPhase.Detailed)?.step == READY }
            })
            step("receiving switched off on it", act = { tap(UiIntent.ReceiveOn(false)) }, until = {
                screen<Layer.JoiningEvent> { it.range()?.receiveOn == false }
                settled { true }
            })
            event
        }

        whenever { openLink(inviteLink(event)) }
        then {
            neverScreen<Layer.JoiningEvent> { it.eventId != event || it.range()?.receiveOn != false }
            never<WorldEvent.MemberJoined>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "An invite opens the app on its join screen",
        scenario = "A different invite replaces the open one",
    )
    fun another_events_invite_replaces_the_open_join_screen() = behaviour {
        val (first, second) = given {
            val first = registeredEvent(name = "Anna's 40th")
            val second = registeredEvent(name = "Ski Trip")
            step("the first invite opened", act = { openLink(inviteLink(first)) }, until = {
                screen<Layer.JoiningEvent> { it.eventId == first && (it.phase as? JoinPhase.Detailed)?.step == READY }
            })
            first to second
        }

        whenever { openLink(inviteLink(second)) }
        then {
            screen<Layer.JoiningEvent> {
                it.eventId == second && (it.phase as? JoinPhase.Detailed)?.eventName == "Ski Trip"
            }
            never<WorldEvent.MemberJoined> { it.event == first }
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Without the app, the invite leads to a store and back",
        scenario = "Installing, then reopening the invite joins",
    )
    fun a_fresh_install_opens_the_invite_on_its_join_screen() = behaviour {
        val event = given {
            registeredEvent().also {
                step("the app installed afresh", act = { host.reinstall() }, until = { settled { true } })
            }
        }

        whenever { openLink(inviteLink(event)) }
        then { screen<Layer.JoiningEvent> { it.eventId == event && (it.phase as? JoinPhase.Detailed)?.step == READY } }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "A damaged invite is reported and changes nothing",
        scenario = "A truncated link shows a passing error",
    )
    fun a_truncated_invite_shows_a_passing_error_on_the_create_screen() = behaviour {
        given { inFront() }

        whenever { openLink(TRUNCATED) }
        then {
            screen<Layer.CreateEvent> { it.error == ScreenMessage.INVALID_LINK }
            neverScreen<Layer.JoiningEvent>()
        }

        whenever { advance(NOTICE_GONE) }
        then { screen<Layer.CreateEvent> { it.error == null } }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "A damaged invite is reported and changes nothing",
        scenario = "A damaged link never touches a membership",
    )
    fun a_damaged_invite_leaves_the_membership_and_its_notice_passes() = behaviour {
        lateinit var before: Seen<Layer.Joined>
        given {
            joinedEvent()
            step("the joined screen read", act = { os.foreground() }, until = {
                before = screen<Layer.Joined>()
                settled { true }
            })
        }

        whenever { openLink(TRUNCATED) }
        then {
            screen<Layer.Joined> { it.notice == ScreenMessage.INVALID_LINK }
            never<WorldEvent.MemberLeft>()
            neverScreen<Layer.Joined> { it.membership != before.value.membership }
        }

        whenever { advance(NOTICE_GONE) }
        then { screen<Layer.Joined> { it.notice == null && it.membership == before.value.membership } }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "A damaged invite is reported and changes nothing",
        scenario = "A damaged link while a join screen is open",
    )
    fun a_damaged_invite_over_a_join_screen_leaves_it_and_its_notice_passes() = behaviour {
        val event = given {
            registeredEvent().also {
                step("its invite opened", act = { openLink(inviteLink(it)) }, until = {
                    screen<Layer.JoiningEvent> { (it.phase as? JoinPhase.Detailed)?.step == READY }
                })
            }
        }

        whenever { openLink(TRUNCATED) }
        then {
            screen<Layer.JoiningEvent> {
                it.eventId == event && it.notice == ScreenMessage.INVALID_LINK && it.phase is JoinPhase.Detailed
            }
        }

        whenever { advance(NOTICE_GONE) }
        then { screen<Layer.JoiningEvent> { it.eventId == event && it.notice == null && it.phase is JoinPhase.Detailed } }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Reopening the current event's invite changes nothing",
        scenario = "A member rescans their own event",
    )
    fun rescanning_the_joined_event_changes_nothing() = behaviour {
        lateinit var before: Seen<Layer.Joined>
        val event = given {
            joinedEvent(Choices(saveToAlbum = false, cutoff = "2026-05-25T00:00:00Z")).also {
                step("the joined screen read", act = { os.foreground() }, until = {
                    before = screen<Layer.Joined>()
                    settled { true }
                })
            }
        }

        whenever {
            openLink(inviteLink(event))
            advance(1.seconds)
        }
        then {
            neverScreen<Layer.Joined> { it.membership != before.value.membership || it.pendingSwitch != null }
            neverScreen<Layer.JoiningEvent>()
            never<WorldEvent.MemberJoined>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "The joined screen offers the invite until the event's photos are deleted",
        scenario = "A host shares the invite before granting photo access",
    )
    fun a_host_without_photo_access_shares_the_invite() = behaviour {
        lateinit var joined: Seen<Layer.Joined>
        given {
            photoAccess(PhotoGrant.DENIED)
            createdEvent()
            step("joined", act = { confirmJoin() }, until = {
                joined = screen<Layer.Joined> { it.inviteUrl != null }
                settled { true }
            })
        }

        whenever { tap(UiIntent.ShareInvite) }
        then { event<WorldEvent.Shared> { it.text == joined.value.inviteUrl } }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "The joined screen offers the invite until the event's photos are deleted",
        scenario = "The QR code is shown on request",
    )
    fun the_qr_code_shows_on_request_and_closing_it_returns_the_joined_screen_unchanged() = behaviour {
        lateinit var before: Seen<WorldEvent.UiChanged>
        given {
            joinedEvent()
            step("the joined screen read", act = { os.foreground() }, until = {
                before = event<WorldEvent.UiChanged> { it.ui.layer is Layer.Joined }
                settled { true }
            })
        }

        whenever { tap(UiIntent.QrOpen) }
        then { event<WorldEvent.UiChanged> { it.ui.overlays.showingQr && it.ui.layer is Layer.Joined } }

        whenever { tap(UiIntent.QrDismiss) }
        then {
            event<WorldEvent.UiChanged> { !it.ui.overlays.showingQr && it.ui.layer == before.value.ui.layer }
            never<WorldEvent.BackendReceived>()
            never<WorldEvent.MemberLeft>()
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "The joined screen offers the invite until the event's photos are deleted",
        scenario = "The QR code is not shown until asked for",
    )
    fun the_joined_screen_opens_without_the_qr_code() = behaviour {
        given { createdEvent() }

        whenever {
            confirmJoin()
            os.foreground()
        }
        then {
            screen<Layer.Joined>()
            never<WorldEvent.UiChanged> { it.ui.overlays.showingQr }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "The joined screen offers the invite until the event's photos are deleted",
        scenario = "The share sheet names the event",
    )
    fun the_share_sheet_is_titled_with_the_events_name() = behaviour {
        lateinit var joined: Seen<Layer.Joined>
        given {
            createdEvent(name = "Anna's 40th")
            step("joined", act = { confirmJoin() }, until = {
                joined = screen<Layer.Joined> { it.inviteUrl != null }
                settled { true }
            })
        }

        whenever { tap(UiIntent.ShareInvite) }
        then { event<WorldEvent.Shared> { it.title == "Anna's 40th" && it.text == joined.value.inviteUrl } }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "The joined screen offers the invite until the event's photos are deleted",
        scenario = "A back-dated event offers its invite",
    )
    fun a_back_dated_event_offers_its_invite() = behaviour {
        given { createdEvent(startsAt = LAST_WEEK_START, endsAt = LAST_WEEK_END) }

        lateinit var joined: Seen<Layer.Joined>
        whenever { confirmJoin() }
        then { joined = screen<Layer.Joined> { it.inviteUrl != null && !it.deleted } }

        whenever { tap(UiIntent.ShareInvite) }
        then { event<WorldEvent.Shared> { it.text == joined.value.inviteUrl } }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "The joined screen offers the invite until the event's photos are deleted",
        scenario = "A deleted event offers no invite",
    )
    fun deletion_takes_the_shown_qr_code_away() = behaviour {
        val event = given {
            joinedEvent().also {
                step("the QR code shown", act = { tap(UiIntent.QrOpen) }, until = {
                    event<WorldEvent.UiChanged> { it.ui.overlays.showingQr }
                    settled { true }
                })
            }
        }

        whenever {
            backend.complete(event)
            os.foreground()
        }
        then {
            event<WorldEvent.UiChanged> {
                (it.ui.layer as? Layer.Joined)?.deleted == true && !it.ui.overlays.showingQr
            }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private suspend fun Given.plainEvents() =
        step("events are created plain", act = { devControls.encryptNewEvents(false) }, until = {})

    /** The fragment form an older build shares, its payload [json]. */
    private fun fragmentForm(json: String): String =
        "$LINK_ORIGIN/join#v=3&d=" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    /** The range the join screen shows, once its event has loaded. */
    private fun Layer.JoiningEvent.range() = (stage as? JoinStage.Loaded)?.range

    private companion object {
        val READY = JoinPhase.Detailed.Step.Ready

        /** A fragment-form invite whose payload was cut off. */
        const val TRUNCATED = "$LINK_ORIGIN/join#v=3&d=eyJldmVudElkIjoiMTIz"

        /** Longer than a passing notice stays. */
        val NOTICE_GONE = 6.seconds

        /** A range over by the time the clock starts, on 1 June. */
        const val LAST_WEEK_START = "2026-05-22T00:00:00"
        const val LAST_WEEK_END = "2026-05-25T00:00:00"
    }
}
