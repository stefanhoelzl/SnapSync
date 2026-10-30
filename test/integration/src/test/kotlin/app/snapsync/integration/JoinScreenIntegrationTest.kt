package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.NetworkState
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.JoinStage
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.NetworkNotice
import app.snapsync.ui.state.OutsideReach
import app.snapsync.ui.state.RangeChoice
import app.snapsync.ui.state.RangeView
import app.snapsync.ui.state.UiIntent
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test

/**
 * **The join screen** (capability `join-event`), over the real composed stack: what it offers once an invite's event
 * has loaded, the range it lets a member choose and the bounds it keeps, what it does without a network, and how it
 * explains photo access before the system ever asks.
 *
 * The clock starts on Monday 1 June 2026, at noon; the default event runs from 15 May to 14 June.
 */
class JoinScreenIntegrationTest {

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "Without a network, the join screen waits for one",
        scenario = "Offline after the details loaded",
    )
    fun the_loaded_join_screen_says_when_the_device_goes_offline() = behaviour {
        given {
            inviteOpened(registeredEvent())
            inFront()
        }

        whenever {
            network(NetworkState.OFFLINE)
            advance(NETWORK_GRACE)
        }
        then { screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed && it.network == NetworkNotice.OFFLINE } }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "Without a network, the join screen waits for one",
        scenario = "Blocked, the guest is offered Settings",
    )
    fun a_blocked_network_on_the_join_screen_says_so_and_settings_open() = behaviour {
        given {
            inviteOpened(registeredEvent())
            inFront()
        }

        whenever {
            network(NetworkState.BLOCKED)
            advance(NETWORK_GRACE)
        }
        then { screen<Layer.JoiningEvent> { it.network == NetworkNotice.BLOCKED } }

        whenever { tap(UiIntent.OpenSettings) }
        then { event<WorldEvent.SettingsOpened>() }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The join screen lets the member decide separately whether to share, whether to receive, and whether to keep an album",
        scenario = "Both switches start on",
    )
    fun a_loaded_event_offers_both_switches_on_and_its_own_dates() = behaviour {
        val event = given { registeredEvent(name = "Ana's 30th") }

        whenever { openLink(inviteLink(event)) }
        then {
            screen<Layer.JoiningEvent> { joining ->
                (joining.phase as? JoinPhase.Detailed)?.eventName == "Ana's 30th" &&
                    joining.range()?.let { it.shareOn && it.receiveOn && it.preset == RangeChoice.EVENT_START } == true
            }
        }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The join screen lets the member decide separately whether to share, whether to receive, and whether to keep an album",
        scenario = "Both off blocks Join with a reason",
    )
    fun both_switches_off_disable_join_and_a_join_is_refused() = behaviour {
        given { createdEvent() }

        whenever { tap(UiIntent.ShareOn(false), UiIntent.ReceiveOn(false)) }
        then {
            screen<Layer.JoiningEvent> { joining ->
                joining.range()?.let { !it.commitEnabled && !it.shareOn && !it.receiveOn } == true
            }
        }

        whenever { tap(UiIntent.ConfirmJoin) }
        then {
            never<WorldEvent.MemberJoined>()
            neverScreen<Layer.Joined>()
            neverScreen<Layer.JoiningEvent> { joining -> joining.range()?.let { it.shareOn || it.receiveOn } == true }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The join screen lets the member decide separately whether to share, whether to receive, and whether to keep an album",
        scenario = "Limited access is told what is shared",
    )
    fun under_limited_access_the_join_screen_shares_the_selection() = behaviour {
        val event = given {
            photoAccess(PhotoGrant.LIMITED)
            registeredEvent()
        }

        whenever { openLink(inviteLink(event)) }
        then { screen<Layer.JoiningEvent> { joining -> joining.range()?.let { it.shareOn && it.sharesSelection } == true } }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The shared capture range is always bounded and chosen by the member",
        scenario = "The default is the event's dates",
    )
    fun the_default_range_is_the_events_own_dates() = behaviour {
        val event = given { registeredEvent(startsAt = FRIDAY_18, endsAt = SUNDAY_23) }

        whenever { openLink(inviteLink(event)) }
        then {
            screen<Layer.JoiningEvent> { joining ->
                joining.range()?.let {
                    it.preset == RangeChoice.EVENT_START &&
                        it.from == LocalDateTime.parse(FRIDAY_18) && it.until == LocalDateTime.parse(SUNDAY_23)
                } == true
            }
        }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The shared capture range is always bounded and chosen by the member",
        scenario = "From now shares from this moment to the event's end",
    )
    fun from_now_shares_from_the_current_time_to_the_end() = behaviour {
        given {
            inviteOpened(registeredEvent())
            inFront()
        }

        whenever { tap(UiIntent.RangePreset(RangeChoice.FROM_NOW)) }
        then {
            screen<Layer.JoiningEvent> { joining ->
                joining.range()?.let {
                    it.preset == RangeChoice.FROM_NOW && it.from > it.windowStart && it.from.date == it.today &&
                        it.until == it.windowEnd
                } == true
            }
        }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The shared capture range is always bounded and chosen by the member",
        scenario = "Now is unavailable outside the window",
    )
    fun from_now_is_not_offered_before_the_start_or_after_the_end() = behaviour {
        val (upcoming, ended) = given {
            registeredEvent(startsAt = FRIDAY_18, endsAt = SUNDAY_23) to
                registeredEvent(startsAt = LAST_WEEK_START, endsAt = LAST_WEEK_END)
        }

        whenever { openLink(inviteLink(upcoming)) }
        then {
            screen<Layer.JoiningEvent> { joining ->
                joining.range()?.let { !it.nowAvailable && it.preset == RangeChoice.EVENT_START } == true
            }
        }

        whenever { tap(UiIntent.CancelJoin) }
        then { screen<Layer.CreateEvent>() }

        whenever { openLink(inviteLink(ended)) }
        then {
            screen<Layer.JoiningEvent> { joining ->
                (joining.phase as? JoinPhase.Detailed)?.step == READY &&
                    joining.range()?.let { !it.nowAvailable && it.preset == RangeChoice.EVENT_START } == true
            }
        }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The shared capture range is always bounded and chosen by the member",
        scenario = "A custom range may start before the event",
    )
    fun a_range_starting_the_day_before_shares_photos_from_then() = behaviour {
        given {
            extensionUploadsOnly()
            inviteOpened(registeredEvent(startsAt = FRIDAY_18, endsAt = SUNDAY_23))
        }

        whenever { setRange(Choices(cutoff = THURSDAY_09)) }
        then { screen<Layer.JoiningEvent> { it.range()?.outside == OutsideReach.BEFORE } }

        whenever { confirmJoin() }
        then {
            screen<Layer.Joined>()
            settled { true }
        }

        whenever {
            advanceTo(SATURDAY)
            library.add("THU", date = THURSDAY_AFTERNOON)
            uploadAll()
        }
        then { event<WorldEvent.BytesStored> { it.asset == "THU" } }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The shared capture range is always bounded and chosen by the member",
        scenario = "A custom range may end after the event",
    )
    fun a_range_ending_after_the_event_shares_photos_until_then() = behaviour {
        given {
            extensionUploadsOnly()
            inviteOpened(registeredEvent(startsAt = FRIDAY_18, endsAt = SUNDAY_23))
        }

        whenever { setRange(Choices(until = TUESDAY_23)) }
        then { screen<Layer.JoiningEvent> { it.range()?.outside == OutsideReach.AFTER } }

        whenever {
            confirmJoin()
            advanceTo(MONDAY_AFTER_NOON)
            library.add("MON", date = MONDAY_AFTER_10)
            uploadAll()
        }
        then { event<WorldEvent.BytesStored> { it.asset == "MON" } }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The shared capture range is always bounded and chosen by the member",
        scenario = "A custom range is at most 30 days long",
    )
    fun the_calendar_allows_no_end_later_than_30_days_after_the_start() = behaviour {
        given { inviteOpened(registeredEvent(startsAt = FIFTEEN_DAYS_START, endsAt = FIFTEEN_DAYS_END)) }

        whenever {
            tap(UiIntent.RangePickerOpen)
            tap(UiIntent.RangeDay(TWENTY_DAYS_BEFORE))
        }
        then {
            screen<Layer.JoiningEvent> { joining ->
                joining.range()?.picker?.lastDay == LocalDate.parse("2026-07-25")
            }
        }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The shared capture range is always bounded and chosen by the member",
        scenario = "A custom range must overlap the event",
    )
    fun the_calendar_allows_no_range_outside_the_event() = behaviour {
        given { inviteOpened(registeredEvent(startsAt = FIFTEEN_DAYS_START, endsAt = FIFTEEN_DAYS_END)) }

        // No start after the event's last day.
        whenever { tap(UiIntent.RangePickerOpen) }
        then { screen<Layer.JoiningEvent> { it.range()?.picker?.lastDay == LocalDate.parse("2026-07-30") } }

        // A start before the event: no last day before the event's first.
        whenever { tap(UiIntent.RangeDay(TWENTY_DAYS_BEFORE)) }
        then {
            screen<Layer.JoiningEvent> { joining ->
                joining.range()?.picker?.let { it.endPending && it.firstEndDay == LocalDate.parse("2026-07-15") } == true
            }
        }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The shared capture range is always bounded and chosen by the member",
        scenario = "A guest's older camera roll is never shared",
    )
    fun a_guest_shares_only_what_was_taken_since_the_event_started() = behaviour {
        given {
            extensionUploadsOnly()
            photoTaken("OLD", date = YEARS_AGO)
            photoTaken("NEW")
            joinedEvent(startsAt = YESTERDAY, endsAt = IN_TWO_DAYS)
        }

        whenever { uploadAll() }
        then {
            event<WorldEvent.BytesStored> { it.asset == "NEW" }
            never<WorldEvent.BytesStored> { it.asset == "OLD" }
            never<WorldEvent.ManifestPublished> { "OLD" in it.added }
        }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "Photo access is explained before the system ever asks",
        scenario = "A first-time guest is told before the system asks",
    )
    fun a_first_time_guest_is_told_access_is_asked_next_and_nothing_asks_yet() = behaviour {
        val event = given {
            photoAccess(PhotoGrant.NOT_DETERMINED)
            registeredEvent()
        }

        whenever { openLink(inviteLink(event)) }
        then {
            screen<Layer.JoiningEvent> { it.asksAccessOnJoin && (it.phase as? JoinPhase.Detailed)?.step == READY }
            never<WorldEvent.LibraryAccessAsked>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "Photo access is explained before the system ever asks",
        scenario = "Confirming raises the system's dialog and joins",
    )
    fun confirming_asks_for_access_and_joins_when_access_is_allowed() = confirmingJoins(PhotoGrant.GRANTED)

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "Photo access is explained before the system ever asks",
        scenario = "Confirming raises the system's dialog and joins",
    )
    fun confirming_asks_for_access_and_joins_when_access_is_limited() = confirmingJoins(PhotoGrant.LIMITED)

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "Photo access is explained before the system ever asks",
        scenario = "Confirming raises the system's dialog and joins",
    )
    fun confirming_asks_for_access_and_joins_when_access_is_refused() = confirmingJoins(PhotoGrant.DENIED)

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "Photo access is explained before the system ever asks",
        scenario = "Cancelling abandons the join without asking",
    )
    fun cancelling_a_first_join_asks_nothing_and_joins_nothing() = behaviour {
        given {
            photoAccess(PhotoGrant.NOT_DETERMINED)
            createdEvent()
        }

        whenever { tap(UiIntent.CancelJoin) }
        then {
            screen<Layer.CreateEvent>()
            never<WorldEvent.LibraryAccessAsked>()
            never<WorldEvent.MemberJoined>()
        }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "Photo access is explained before the system ever asks",
        scenario = "A user who already answered the system joins without a dialog",
    )
    fun a_member_who_granted_access_joins_with_no_dialog() = answeredJoinsPlainly(PhotoGrant.GRANTED)

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "Photo access is explained before the system ever asks",
        scenario = "A user who already answered the system joins without a dialog",
    )
    fun a_member_who_refused_access_joins_with_no_dialog() = answeredJoinsPlainly(PhotoGrant.DENIED)

    // ---- helpers --------------------------------------------------------------------------------

    private fun confirmingJoins(answer: PhotoGrant) = behaviour {
        val event = given {
            photoAccess(PhotoGrant.NOT_DETERMINED)
            step("the system's dialog will be answered $answer", act = { library.requestAnswer(answer) }, until = {})
            inviteOpened(registeredEvent())
        }

        whenever { confirmJoin(Choices(saveToAlbum = false)) }
        then {
            event<WorldEvent.LibraryAccessAsked>()
            event<WorldEvent.MemberJoined> { it.event == event }
            screen<Layer.Joined> { it.membership.eventId == event && !it.membership.savesToAlbum }
        }
    }

    private fun answeredJoinsPlainly(grant: PhotoGrant) = behaviour {
        val event = given {
            photoAccess(grant)
            registeredEvent()
        }

        whenever { openLink(inviteLink(event)) }
        then { screen<Layer.JoiningEvent> { !it.asksAccessOnJoin && (it.phase as? JoinPhase.Detailed)?.step == READY } }

        whenever { confirmJoin() }
        then {
            screen<Layer.Joined> { it.membership.eventId == event }
            never<WorldEvent.LibraryAccessAsked>()
        }
    }

    /** [event]'s invite opened, and its join screen loaded. Answers [event]. */
    private suspend fun Given.inviteOpened(event: String): String {
        step("the invite opened", act = { openLink(inviteLink(event)) }, until = {
            screen<Layer.JoiningEvent> { (it.phase as? JoinPhase.Detailed)?.step == READY }
            settled { true }
        })
        return event
    }

    /** The range the join screen shows, once its event has loaded. */
    private fun Layer.JoiningEvent.range(): RangeView? = (stage as? JoinStage.Loaded)?.range

    private companion object {
        val READY = JoinPhase.Detailed.Step.Ready

        /** Longer than the network watch's grace before it reports a change. */
        val NETWORK_GRACE = kotlin.time.Duration.parse("6s")

        /** An event from Friday 18:00 to Sunday 23:00, later this week. */
        const val FRIDAY_18 = "2026-06-05T18:00:00"
        const val SUNDAY_23 = "2026-06-07T23:00:00"
        const val THURSDAY_09 = "2026-06-04T09:00:00Z"
        const val THURSDAY_AFTERNOON = "2026-06-04T15:00:00Z"
        const val SATURDAY = "2026-06-06T12:00:00Z"
        const val TUESDAY_23 = "2026-06-09T23:00:00Z"
        const val MONDAY_AFTER_10 = "2026-06-08T10:00:00Z"
        const val MONDAY_AFTER_NOON = "2026-06-08T12:00:00Z"

        /** An event over by now. */
        const val LAST_WEEK_START = "2026-05-22T00:00:00"
        const val LAST_WEEK_END = "2026-05-25T00:00:00"

        /** An event lasting 15 days, and a start 20 days before it. */
        const val FIFTEEN_DAYS_START = "2026-07-15T00:00:00"
        const val FIFTEEN_DAYS_END = "2026-07-30T00:00:00"
        val TWENTY_DAYS_BEFORE = LocalDate.parse("2026-06-25")

        /** An event that started yesterday, and a photo from years before it. */
        const val YESTERDAY = "2026-05-31T00:00:00"
        const val IN_TWO_DAYS = "2026-06-03T00:00:00"
        const val YEARS_AGO = "2021-08-14T10:00:00Z"
    }
}
