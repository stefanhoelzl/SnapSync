package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.ui.state.EventTiming
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test

/**
 * The joined screen's lines that move with the clock alone, on a screen left open while the clock crosses a bound:
 * nothing else changes, so only the screen's own tick — on the app's lanes, keeping the app's clock — moves them.
 */
class ClockLinesIntegrationTest {

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A not-yet-started event says when it starts",
        scenario = "The line retires itself",
    )
    fun the_not_started_line_retires_itself_once_the_start_passes() = behaviour {
        given {
            joinedEvent(startsAt = IN_AN_HOUR, endsAt = IN_TWO_DAYS)
            inFront()
        }
        then { screen<Layer.Joined> { it.health == SyncHealth.NotStarted } }

        whenever { advanceTo(A_MINUTE_PAST_THE_HOUR) }
        then { screen<Layer.Joined> { it.health != SyncHealth.NotStarted } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "An ended event is marked without stopping sync",
        scenario = "The marker appears while open",
    )
    fun the_ended_marker_appears_while_the_screen_is_open() = behaviour {
        given {
            joinedEvent(startsAt = TWO_DAYS_AGO, endsAt = IN_AN_HOUR)
            inFront()
        }
        then { screen<Layer.Joined> { it.timing is EventTiming.Running } }

        whenever { advanceTo(A_MINUTE_PAST_THE_HOUR) }
        then { screen<Layer.Joined> { it.timing == EventTiming.Ended } }
    }

    private companion object {
        /** Bounds around the clock's start, 1 June at noon. */
        const val TWO_DAYS_AGO = "2026-05-30T12:00:00"
        const val IN_AN_HOUR = "2026-06-01T13:00:00"
        const val IN_TWO_DAYS = "2026-06-03T12:00:00"
        const val A_MINUTE_PAST_THE_HOUR = "2026-06-01T13:01:00Z"
    }
}
