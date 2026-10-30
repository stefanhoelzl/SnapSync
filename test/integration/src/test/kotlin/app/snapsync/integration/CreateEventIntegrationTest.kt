package app.snapsync.integration

import app.snapsync.control.Taps
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.BackendCall
import app.snapsync.levers.BackendFault
import app.snapsync.levers.NetworkState
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.JoinStage
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.NetworkNotice
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.UiIntent
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * **Creating an event** (capability `create-event`), over the real composed stack — what crosses from the create form
 * to the service and the join screen: which forms are never submitted, the start the created event carries, the
 * create without photo access, a create the service refuses or cannot reach, the create screen without a network, and
 * the question an event already over asks first. How the form fills itself in is the screen's, held by its own tests.
 */
class CreateEventIntegrationTest {

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "The front screen offers to start an event",
        scenario = "A user with no event sees the create screen",
    )
    fun a_device_in_no_event_opens_on_an_empty_create_form() = behaviour {
        whenever { host.relaunch() }
        then {
            screen<Layer.CreateEvent> { it.form.name == "" && it.error == null && it.network == null }
            neverScreen<Layer.Joined>()
            neverScreen<Layer.JoiningEvent>()
        }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "The event name is required and at most 100 characters",
        scenario = "An empty name cannot be submitted",
    )
    fun a_blank_name_is_never_submitted() = behaviour {
        given { inFront() }

        whenever { tap(Taps.create("   ", START, END, OPEN_UNTIL_DAYS)) }
        then {
            screen<Layer.CreateEvent> { !it.form.canCreate }
            never<WorldEvent.BackendReceived> { it.operation == CREATE }
            neverScreen<Layer.JoiningEvent>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "The host chooses the event's date range and its end time",
        scenario = "A range longer than 30 days cannot be chosen",
    )
    fun no_event_longer_than_30_days_is_created() = behaviour {
        given { inFront() }

        lateinit var joining: Seen<Layer.JoiningEvent>
        whenever { tap(Taps.create(NAME, START, START_PLUS_40_DAYS, OPEN_UNTIL_DAYS)) }
        then { joining = screen<Layer.JoiningEvent> { it.stage is JoinStage.Loaded } }

        val range = (joining.value.stage as JoinStage.Loaded).range
        kotlin.test.assertTrue(
            range.windowEnd.date <= range.windowStart.date.plus(DatePeriod(days = 30)),
            "the created event lasts at most 30 days: ${range.windowStart}..${range.windowEnd}",
        )
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "The host chooses the event's date range and its end time",
        scenario = "An inverted range cannot be chosen",
    )
    fun no_event_ending_before_it_starts_is_created() = behaviour {
        given { inFront() }

        lateinit var joining: Seen<Layer.JoiningEvent>
        whenever { tap(Taps.create(NAME, SAME_DAY_START, SAME_DAY_BEFORE_THE_START, OPEN_UNTIL_DAYS)) }
        then {
            joining = screen<Layer.JoiningEvent> { it.stage is JoinStage.Loaded }
        }

        val range = (joining.value.stage as JoinStage.Loaded).range
        kotlin.test.assertTrue(range.windowEnd > range.windowStart, "the created event ends after it starts: $range")
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "The start follows the clock until the host chooses the range",
        scenario = "An untouched start keeps up with the clock",
    )
    fun an_untouched_start_moves_with_the_clock_into_the_created_event() = behaviour {
        lateinit var opened: Seen<Layer.CreateEvent>
        given {
            step("the create screen open", act = { host.relaunch() }, until = {
                opened = screen<Layer.CreateEvent>()
                settled { true }
            })
        }
        val start = opened.value.form.picker.from

        whenever {
            tap(UiIntent.CreateName(NAME))
            advance(10.minutes)
            os.foreground()
        }
        then { screen<Layer.CreateEvent> { it.form.picker.from == start.plusMinutes(10) } }

        whenever { tap(UiIntent.RangeUntilHour(23), UiIntent.RangeUntilMinute(30), UiIntent.ConfirmCreate) }
        then {
            screen<Layer.JoiningEvent> { joining ->
                (joining.stage as? JoinStage.Loaded)?.range?.windowStart == start.plusMinutes(10)
            }
        }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "Creating needs no photo access",
        scenario = "A host who never granted photo access creates an event",
    )
    fun a_host_never_asked_for_photo_access_creates_without_a_dialog() = createsWithout(PhotoGrant.NOT_DETERMINED)

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "Creating needs no photo access",
        scenario = "A host who never granted photo access creates an event",
    )
    fun a_host_who_refused_photo_access_creates_without_a_dialog() = createsWithout(PhotoGrant.DENIED)

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "The creator joins through the same join screen as every guest",
        scenario = "The host gets no exemption from the window",
    )
    fun the_hosts_calendar_keeps_a_guests_bounds() = behaviour {
        given { createdEvent(startsAt = FIFTEEN_DAYS_START, endsAt = FIFTEEN_DAYS_END) }

        whenever { tap(UiIntent.RangePickerOpen) }
        then {
            screen<Layer.JoiningEvent> { joining ->
                (joining.stage as? JoinStage.Loaded)?.range?.picker?.let {
                    it.firstDay == LocalDate.parse("2026-06-15") && it.lastDay == LocalDate.parse("2026-07-30")
                } == true
            }
        }

        whenever { tap(UiIntent.RangeDay(LocalDate.parse("2026-06-25"))) }
        then {
            screen<Layer.JoiningEvent> { joining ->
                (joining.stage as? JoinStage.Loaded)?.range?.picker?.let {
                    it.lastDay == LocalDate.parse("2026-07-25") && it.firstEndDay == LocalDate.parse("2026-07-15")
                } == true
            }
            never<WorldEvent.MemberJoined>()
        }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "The creator joins through the same join screen as every guest",
        scenario = "Cancelling after creating leaves the host in no event",
    )
    fun cancelling_the_new_events_join_screen_leaves_no_event() = behaviour {
        given { createdEvent() }

        whenever { tap(UiIntent.CancelJoin) }
        then {
            screen<Layer.CreateEvent>()
            never<WorldEvent.MemberJoined>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "A failed create says so and changes nothing",
        scenario = "Offline create reports the server as unreachable",
    )
    fun a_create_as_the_network_drops_says_the_server_was_unreachable() = behaviour {
        given { inFront() }

        whenever {
            network.set(NetworkState.OFFLINE)
            backend.fail(CREATE, BackendFault.UNREACHABLE)
            tap(Taps.create(NAME, START, END, OPEN_UNTIL_DAYS))
        }
        then {
            screen<Layer.CreateEvent> { it.error == ScreenMessage.SERVICE_UNREACHABLE }
            neverScreen<Layer.JoiningEvent>()
            never<WorldEvent.EventCreated>()
        }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "A failed create says so and changes nothing",
        scenario = "A rejected name is reported as such",
    )
    fun a_name_the_service_refuses_is_told_as_such() = behaviour {
        given { inFront() }

        whenever { tap(Taps.create("x".repeat(101), START, END, OPEN_UNTIL_DAYS)) }
        then {
            screen<Layer.CreateEvent> { it.error == ScreenMessage.CREATE_NAME_REFUSED }
            never<WorldEvent.EventCreated>()
        }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "Without a network, Create waits",
        scenario = "Offline, Create is unavailable",
    )
    fun opening_the_app_offline_says_so_on_the_create_screen() = behaviour {
        given {
            inFront()
            step("offline", act = {
                network(NetworkState.OFFLINE)
                advance(NETWORK_GRACE)
            }, until = { settled { true } })
        }

        whenever {
            host.relaunch()
            os.foreground()
            advance(NETWORK_GRACE)
        }
        then { screen<Layer.CreateEvent> { it.network == NetworkNotice.OFFLINE } }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "Without a network, Create waits",
        scenario = "Blocked, the user is offered Settings",
    )
    fun a_blocked_network_is_named_on_the_create_screen_and_settings_open() = behaviour {
        given { inFront() }

        whenever {
            network(NetworkState.BLOCKED)
            advance(NETWORK_GRACE)
        }
        then { screen<Layer.CreateEvent> { it.network == NetworkNotice.BLOCKED } }

        whenever { tap(UiIntent.OpenSettings) }
        then { event<WorldEvent.SettingsOpened>() }
    }

    @Test
    @Verifies(spec = "create-event", requirement = "Without a network, Create waits", scenario = "The network returns")
    fun the_network_returning_clears_the_notice() = behaviour {
        given {
            inFront()
            step("offline, and told so", act = {
                network(NetworkState.OFFLINE)
                advance(NETWORK_GRACE)
            }, until = { screen<Layer.CreateEvent> { it.network == NetworkNotice.OFFLINE } })
        }

        whenever {
            network(NetworkState.ONLINE)
            advance(NETWORK_GRACE)
        }
        then { screen<Layer.CreateEvent> { it.network == null && it.error == null } }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "Without a network, Create waits",
        scenario = "A create under way is not interrupted",
    )
    fun a_create_under_way_finishes_before_the_notice_shows() = behaviour {
        given {
            inFront()
            step("the create held", act = { backend.hold(BackendCall.CREATE) }, until = {})
            step("Create tapped", act = { tap(Taps.create(NAME, START, END, OPEN_UNTIL_DAYS)) }, until = {
                screen<Layer.CreatingEvent>()
            })
        }

        whenever {
            network.set(NetworkState.OFFLINE)
            advance(NETWORK_GRACE)
        }
        then {
            neverScreen<Layer.CreateEvent>()
            settled { true }
        }

        whenever { backend.release(BackendCall.CREATE) }
        then {
            event<WorldEvent.EventCreated>()
            screen<Layer.JoiningEvent>()
            neverScreen<Layer.CreateEvent>()
        }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "Creating an event that is already over asks how long guests have to join",
        scenario = "A weekend already over",
    )
    fun an_event_already_over_asks_how_long_guests_may_join_before_anything_is_created() =
        behaviour(startAt = TWELFTH_OF_OCTOBER) {
            given { inFront() }

            whenever { tap(Taps.create(NAME, WEEKEND_START, WEEKEND_END, 7).dropLast(1)) }
            then {
                screen<Layer.CreateEvent> { create ->
                    create.form.keepOpen?.choices?.map { it.days to it.until.toString() } == listOf(
                        1 to "2026-10-13",
                        7 to "2026-10-19",
                        14 to "2026-10-26",
                        30 to "2026-11-11",
                    )
                }
                never<WorldEvent.BackendReceived> { it.operation == CREATE }
                settled { true }
            }
        }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "Creating an event that is already over asks how long guests have to join",
        scenario = "A choice creates the event",
    )
    fun choosing_how_long_guests_may_join_creates_the_event() = behaviour(startAt = TWELFTH_OF_OCTOBER) {
        given {
            inFront()
            step("asked how long guests may join", act = {
                tap(Taps.create(NAME, WEEKEND_START, WEEKEND_END, 7).dropLast(1))
            }, until = { screen<Layer.CreateEvent> { it.form.keepOpen != null } })
        }

        whenever { tap(UiIntent.KeepOpenFor(7)) }
        then {
            event<WorldEvent.EventCreated> { it.name == NAME }
            screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed }
        }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "Creating an event that is already over asks how long guests have to join",
        scenario = "Cancelling keeps the draft",
    )
    fun dismissing_the_question_keeps_the_draft_and_creates_nothing() = behaviour(startAt = TWELFTH_OF_OCTOBER) {
        lateinit var asked: Seen<Layer.CreateEvent>
        given {
            inFront()
            step("asked how long guests may join", act = {
                tap(Taps.create(NAME, WEEKEND_START, WEEKEND_END, 7).dropLast(1))
            }, until = {
                asked = screen<Layer.CreateEvent> { it.form.keepOpen != null }
                settled { true }
            })
        }

        whenever { tap(UiIntent.KeepOpenDismiss) }
        then {
            screen<Layer.CreateEvent> {
                it.form.keepOpen == null && it.form.name == NAME && it.form.picker == asked.value.form.picker
            }
            never<WorldEvent.BackendReceived> { it.operation == CREATE }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "Creating an event that is already over asks how long guests have to join",
        scenario = "An event still running needs no choice",
    )
    fun an_event_still_running_is_created_without_the_question() = behaviour {
        given { inFront() }

        whenever { tap(Taps.create(NAME, YESTERDAY, IN_AN_HOUR, OPEN_UNTIL_DAYS).dropLast(1)) }
        then {
            event<WorldEvent.EventCreated> { it.name == NAME }
            screen<Layer.JoiningEvent>()
            neverScreen<Layer.CreateEvent> { it.form.keepOpen != null }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun createsWithout(grant: PhotoGrant) = behaviour {
        given {
            photoAccess(grant)
            inFront()
        }

        whenever { createEvent(NAME, Rig.WINDOW_START, Rig.WINDOW_END) }
        then {
            event<WorldEvent.EventCreated> { it.name == NAME }
            screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed }
            never<WorldEvent.LibraryAccessAsked>()
        }
    }

    private fun LocalDateTime.plusMinutes(minutes: Int): LocalDateTime {
        val total = hour * 60 + minute + minutes
        return LocalDateTime(date.plus(DatePeriod(days = total / (24 * 60))), LocalTime((total / 60) % 24, total % 60))
    }

    private companion object {
        const val NAME = "Anna's Birthday"
        const val CREATE = "createEvent"
        const val OPEN_UNTIL_DAYS = 7

        /** Longer than the network watch's grace before it reports a change. */
        val NETWORK_GRACE = 6.seconds

        val START: LocalDateTime = LocalDateTime.parse(Rig.WINDOW_START)
        val END: LocalDateTime = LocalDateTime.parse(Rig.WINDOW_END)
        val START_PLUS_40_DAYS: LocalDateTime = LocalDateTime.parse("2026-06-24T00:00:00")

        /** Today, from 18:00 to 17:00. */
        val SAME_DAY_START: LocalDateTime = LocalDateTime.parse("2026-06-01T18:00:00")
        val SAME_DAY_BEFORE_THE_START: LocalDateTime = LocalDateTime.parse("2026-06-01T17:00:00")

        /** An event lasting 15 days, a guest's calendar bounds around it. */
        const val FIFTEEN_DAYS_START = "2026-07-15T00:00:00"
        const val FIFTEEN_DAYS_END = "2026-07-30T00:00:00"

        /** A weekend already over on the 12th of October. */
        const val TWELFTH_OF_OCTOBER = "2026-10-12T10:00:00Z"
        val WEEKEND_START: LocalDateTime = LocalDateTime.parse("2026-10-02T18:00:00")
        val WEEKEND_END: LocalDateTime = LocalDateTime.parse("2026-10-04T20:00:00")

        /** From yesterday until an hour from the clock's start (1 June, noon). */
        val YESTERDAY: LocalDateTime = LocalDateTime.parse("2026-05-31T09:00:00")
        val IN_AN_HOUR: LocalDateTime = LocalDateTime.parse("2026-06-01T15:00:00")
    }
}
