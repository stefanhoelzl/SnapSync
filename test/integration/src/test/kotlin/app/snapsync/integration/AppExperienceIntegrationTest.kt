package app.snapsync.integration

import app.snapsync.control.Taps
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.BackendCall
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.JoinStage
import app.snapsync.ui.state.JoinedSurface
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RangeChoice
import app.snapsync.ui.state.ShareCount
import app.snapsync.ui.state.SyncHealth
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.offersMenu
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours

/**
 * **How the app behaves as an app** (capability `app-experience`), over the real composed stack — what the core decides
 * of it: the first frame of a launch, the place kept across backgrounding, where the app menu is offered, a tap that
 * runs once, and the range picker's guidance. How each screen draws it is the screen's, held by its own tests.
 */
class AppExperienceIntegrationTest {

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "The first frame is never a guessed state",
        scenario = "Joined launch",
    )
    fun a_joined_launch_opens_on_the_joined_screen_with_the_neutral_line() = behaviour {
        given {
            joinedEvent()
            photos("P1")
        }

        whenever { host.relaunch() }
        then {
            screen<Layer.Joined> { it.health == SyncHealth.Loading }
            neverScreen<Layer.CreateEvent>()
            neverScreen<Layer.Joined> { it.health == SyncHealth.InSync }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "The app keeps its place across backgrounding",
        scenario = "Returning to an open settings surface",
    )
    fun the_settings_left_open_are_still_open_hours_later() = behaviour {
        lateinit var open: Seen<Layer.Joined>
        given {
            joinedEvent()
            step("the settings open", act = { tap(UiIntent.OpenReconfigure) }, until = {
                open = screen<Layer.Joined> { it.surface is JoinedSurface.Reconfigure }
                settled { true }
            })
        }

        whenever {
            os.background()
            advance(3.hours)
            os.foreground()
        }
        then {
            neverScreen<Layer.Joined> { it.choices() != open.value.choices() }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "The app menu is one tap away on every screen",
        scenario = "The menu before joining",
    )
    fun the_menu_opens_from_the_create_screen() = behaviour {
        given { inFront() }

        whenever { tap(UiIntent.MenuOpen) }
        then {
            event<WorldEvent.UiChanged> { it.ui.overlays.menuOpen && it.ui.layer is Layer.CreateEvent && it.ui.layer.offersMenu }
        }
    }

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "The app menu is one tap away on every screen",
        scenario = "A join in progress",
    )
    fun no_menu_while_a_create_is_under_way() = behaviour {
        given {
            inFront()
            step("the create held", act = { backend.hold(BackendCall.CREATE) }, until = {})
            step("Create tapped", act = {
                createEvent(Defaults.EVENT_NAME, Defaults.WINDOW_START, Defaults.WINDOW_END)
            }, until = {
                screen<Layer.CreatingEvent>()
            })
        }

        whenever { tap(UiIntent.MenuOpen) }
        then {
            never<WorldEvent.UiChanged> { it.ui.overlays.menuOpen }
            settled { true }
        }

        whenever { backend.release(BackendCall.CREATE) }
        then { event<WorldEvent.UiChanged> { it.ui.layer is Layer.JoiningEvent && it.ui.layer.offersMenu } }
    }

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "The app menu is one tap away on every screen",
        scenario = "Flipping the mobile-data switch",
    )
    fun flipping_mobile_data_off_keeps_the_menu_open() = behaviour {
        given {
            joinedEvent()
            step("the menu open", act = { tap(UiIntent.MenuOpen) }, until = {
                event<WorldEvent.UiChanged> { it.ui.overlays.menuOpen }
                settled { true }
            })
        }

        whenever { tap(UiIntent.MobileData(false)) }
        then { event<WorldEvent.UiChanged> { !it.ui.mobileData.on && it.ui.overlays.menuOpen } }
    }

    @Test
    @Verifies(spec = "app-experience", requirement = "Taps keep working and never fire twice", scenario = "Double tap")
    fun create_tapped_twice_creates_once() = behaviour {
        given {
            inFront()
            step("the create held", act = { backend.hold(BackendCall.CREATE) }, until = {})
        }

        whenever {
            tap(Taps.create(Defaults.EVENT_NAME, START, END, 7).dropLast(1) + UiIntent.ConfirmCreate)
            backend.release(BackendCall.CREATE)
        }
        then {
            times(1) { event<WorldEvent.EventCreated>() }
            screen<Layer.JoiningEvent>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "Explanations always match the current choices",
        scenario = "Changing a choice updates its explanation",
    )
    fun choosing_another_range_updates_what_will_be_shared() = behaviour {
        lateinit var before: Seen<Layer.JoiningEvent>
        given {
            val event = registeredEvent()
            step("its join screen loaded", act = { openLink(inviteLink(event)) }, until = {
                before = screen<Layer.JoiningEvent> { it.range()?.shareCount is ShareCount.Ready }
                settled { true }
            })
        }

        whenever { tap(UiIntent.RangePreset(RangeChoice.FROM_NOW)) }
        then {
            screen<Layer.JoiningEvent> { joining ->
                joining.range()?.let {
                    it.preset == RangeChoice.FROM_NOW && it.from != before.value.range()?.from && it.shareCount is ShareCount.Ready
                } == true
            }
        }
    }

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "Picking a date range is guided and only offers what is allowed",
        scenario = "A shared range around the event",
    )
    fun the_picker_marks_the_event_and_keeps_the_range_bounded() = behaviour {
        given { joinScreenOf(FIFTEEN_DAYS_START, FIFTEEN_DAYS_END) }

        whenever { tap(UiIntent.RangePickerOpen) }
        then {
            screen<Layer.JoiningEvent> { joining ->
                joining.range()?.picker?.let {
                    it.markedDays?.first == LocalDate.parse("2026-07-15") && it.markedDays?.last == LocalDate.parse("2026-07-30") &&
                        it.firstDay == LocalDate.parse("2026-06-15") && it.lastDay == LocalDate.parse("2026-07-30")
                } == true
            }
        }

        whenever {
            tap(
                UiIntent.RangeDay(LocalDate.parse("2026-07-10")),
                UiIntent.RangeDay(LocalDate.parse("2026-07-20")),
                UiIntent.RangePickerConfirm,
            )
        }
        then {
            screen<Layer.JoiningEvent> { joining ->
                joining.range()?.let { it.picker == null && it.preset == RangeChoice.CUSTOM && it.until > it.from } == true
            }
        }
    }

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "Picking a date range is guided and only offers what is allowed",
        scenario = "Third tap starts over",
    )
    fun a_third_day_tapped_starts_a_new_range() = behaviour {
        given { joinScreenOf(FIFTEEN_DAYS_START, FIFTEEN_DAYS_END) }

        whenever {
            tap(
                UiIntent.RangePickerOpen,
                UiIntent.RangeDay(LocalDate.parse("2026-07-16")),
                UiIntent.RangeDay(LocalDate.parse("2026-07-20")),
                UiIntent.RangeDay(LocalDate.parse("2026-07-22")),
            )
        }
        then {
            screen<Layer.JoiningEvent> { joining ->
                joining.range()?.picker?.let { it.from.date == LocalDate.parse("2026-07-22") && it.endPending } == true
            }
        }
    }

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "Picking a date range is guided and only offers what is allowed",
        scenario = "Cancel keeps the old choice",
    )
    fun cancelling_the_picker_keeps_the_range_as_it_was() = behaviour {
        val before = given { joinScreenOf(FIFTEEN_DAYS_START, FIFTEEN_DAYS_END) }

        whenever {
            tap(
                UiIntent.RangePickerOpen,
                UiIntent.RangeDay(LocalDate.parse("2026-07-16")),
                UiIntent.RangeDay(LocalDate.parse("2026-07-20")),
                UiIntent.RangePickerDismiss,
            )
        }
        then {
            screen<Layer.JoiningEvent> { joining ->
                joining.range()?.let {
                    it.picker == null && it.preset == before.preset && it.from == before.from && it.until == before.until
                } == true
            }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** An event from [startsAt] to [endsAt], its join screen loaded. Answers the range it opened on. */
    private suspend fun Given.joinScreenOf(startsAt: String, endsAt: String): app.snapsync.ui.state.RangeView {
        val event = registeredEvent(startsAt = startsAt, endsAt = endsAt)
        lateinit var loaded: Seen<Layer.JoiningEvent>
        step("its join screen loaded", act = { openLink(inviteLink(event)) }, until = {
            loaded = screen<Layer.JoiningEvent> { (it.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.Ready }
            settled { true }
        })
        return loaded.value.range()!!
    }

    /** The choices the event's settings show, their live photo count aside; `null` while they are closed. */
    private fun Layer.Joined.choices() =
        (surface as? JoinedSurface.Reconfigure)?.range?.copy(shareCount = ShareCount.Counting)

    /** The range the join screen shows, once its event has loaded. */
    private fun Layer.JoiningEvent.range() = (stage as? JoinStage.Loaded)?.range

    private companion object {
        val START: LocalDateTime = LocalDateTime.parse(Defaults.WINDOW_START)
        val END: LocalDateTime = LocalDateTime.parse(Defaults.WINDOW_END)

        /** An event lasting 15 days, ahead of the clock. */
        const val FIFTEEN_DAYS_START = "2026-07-15T00:00:00"
        const val FIFTEEN_DAYS_END = "2026-07-30T00:00:00"
    }
}
