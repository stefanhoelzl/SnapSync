package app.snapsync.contracts

import app.snapsync.ports.Clock
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The state a clock can be found in. */
enum class ClockState {
    /** A running process on a device whose clock is set. */
    RUNNING,
}

/**
 * What the process's [Clock] promises: the wall clock now, and the device's zone. A clock frozen at an instant, or one
 * an epoch away, fails it: the reading is held to an independent one taken around it.
 */
object ClockContract : Contract<ClockState, Clock>("Clock") {

    private val TOLERANCE = 5.seconds

    override val clauses = clauses {

        clause(
            "RUNNING_NOW_IS_THE_WALL_CLOCK_AND_THE_ZONE_IS_THE_DEVICES",
            ClockState.RUNNING,
            covers = cells {
                on<Clock> {
                    answers(Clock::now).returns()
                    answers(Clock::timeZone).returns()
                }
            },
        ) { clock ->
            val before = kotlin.time.Clock.System.now()
            val now = clock.now()
            val after = kotlin.time.Clock.System.now()
            assertTrue(now >= before - TOLERANCE && now <= after + TOLERANCE, "now is the wall clock's: $now")
            assertTrue(clock.timeZone().id.isNotBlank(), "the device is in a zone")
        }
    }
}
