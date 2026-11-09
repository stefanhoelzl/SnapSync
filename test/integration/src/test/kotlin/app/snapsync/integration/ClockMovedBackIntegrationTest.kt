package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **A background wake asks the event at most once an hour — unless the clock moved back** (decision record
 * `changes/timely-background-receiving`, D5), counted at the backend: a recorded read that lies ahead of now says
 * nothing about the hour, so the wake reads.
 */
@Verifies(spec = "delivery", requirement = "Photos travel without the app being opened")
class ClockMovedBackIntegrationTest {

    @Test
    fun a_heartbeat_after_the_clock_moved_back_reads_the_union() = behaviour {
        given {
            joinedEvent()
            step("a heartbeat at $T0", act = {
                advanceTo(T0)
                heartbeat()
            }, until = { settled { true } })
        }

        whenever {
            advanceTo(HALF_AN_HOUR_BEFORE_T0)
            heartbeat()
        }
        then { times(1) { event<WorldEvent.BackendReceived> { it.operation == UNION_READ } } }
    }

    private companion object {
        /** The union read's operation. */
        const val UNION_READ = "eventFiles"

        const val T0 = "2026-06-10T12:00:00Z"
        const val HALF_AN_HOUR_BEFORE_T0 = "2026-06-10T11:30:00Z"
    }
}
