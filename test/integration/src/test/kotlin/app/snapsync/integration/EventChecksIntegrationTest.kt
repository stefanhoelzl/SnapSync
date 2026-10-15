package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import kotlin.test.Test

/**
 * **A background wake asks the event at most once an hour** (decision record `changes/timely-background-receiving`,
 * D4–D5), counted at the backend: a heartbeat reads the union when none was read within the hour, and — after the
 * event's end — its state the same way; a push reads both whenever it comes, and resets the hour. A join and a
 * reconfigure read the union once each.
 */
class EventChecksIntegrationTest {

    @Test
    fun heartbeats_read_the_union_at_most_once_an_hour() = behaviour {
        given {
            joinedEvent()
            // Long after the join's own reads: this wake asks.
            step("a heartbeat at $T0", act = {
                advanceTo(T0)
                heartbeat()
            }, until = { settled { true } })
        }

        whenever {
            advanceTo(T0_PLUS_10_MIN)
            heartbeat()
        }
        then { never<WorldEvent.BackendReceived> { it.operation == UNION_READ } } // ten minutes later it does not

        whenever {
            advanceTo(T0_PLUS_1_H)
            heartbeat()
        }
        then {
            times(
                1,
            ) { event<WorldEvent.BackendReceived> { it.operation == UNION_READ } }
        } // an hour later it asks again
    }

    @Test
    fun a_push_reads_the_union_whenever_it_comes_and_resets_the_hour() = behaviour {
        val event = given {
            joinedEvent().also {
                step("a heartbeat at $T0", act = {
                    advanceTo(T0)
                    heartbeat()
                }, until = { settled { true } })
            }
        }

        whenever {
            advanceTo(T0_PLUS_10_MIN)
            os.silentPush(event)
        }
        then { times(1) { event<WorldEvent.BackendReceived> { it.operation == UNION_READ } } }

        // The wake after a push reads nothing.
        whenever {
            advanceTo(T0_PLUS_20_MIN)
            heartbeat()
        }
        then { never<WorldEvent.BackendReceived> { it.operation == UNION_READ } }
    }

    @Test
    fun after_the_end_heartbeats_read_the_event_at_most_once_an_hour_and_a_push_always() = behaviour(startAt = DURING) {
        val event = given {
            joinedEvent(startsAt = SHORT_START, endsAt = SHORT_END).also {
                // A second member that never settles keeps the event open, so this one stays joined to be woken.
                fellowMember(DEV_F, "FQ")
                step("a heartbeat after the end", act = {
                    advanceTo(AFTER_THE_END)
                    heartbeat()
                }, until = { settled { true } })
            }
        }

        whenever {
            advanceTo(AFTER_THE_END_PLUS_10_MIN)
            heartbeat()
        }
        then { never<WorldEvent.BackendReceived> { it.operation == EVENT_READ } } // a wake within the hour reads no state

        whenever { os.silentPush(event) }
        then { times(1) { event<WorldEvent.BackendReceived> { it.operation == EVENT_READ } } }
    }

    @Test
    fun a_join_reads_the_union_once() = behaviour {
        given { createdEvent() }
        // The join read it before the membership was saved; nothing after the save reads it again: the reconcile, the
        // arm, the gather.
        whenever { confirmJoin() }
        then {
            screen<Layer.Joined>()
            times(1) { event<WorldEvent.BackendReceived> { it.operation == UNION_READ } }
        }
    }

    @Test
    fun a_reconfigure_reads_the_union_once() = behaviour {
        given { joinedEvent(Choices(saveToAlbum = false)) }
        // The reconcile reads it; the arm and the gather do not.
        whenever { reconfigure(Choices(saveToAlbum = true)) }
        then {
            screen<Layer.Joined> { it.membership.savesToAlbum }
            times(1) { event<WorldEvent.BackendReceived> { it.operation == UNION_READ } }
        }
    }

    private companion object {
        /** The union read's operation. */
        const val UNION_READ = "eventFiles"

        /** The event read's operation: its details and state. */
        const val EVENT_READ = "getEvent"

        const val T0 = "2026-06-10T12:00:00Z"
        const val T0_PLUS_10_MIN = "2026-06-10T12:10:00Z"
        const val T0_PLUS_20_MIN = "2026-06-10T12:20:00Z"
        const val T0_PLUS_1_H = "2026-06-10T13:00:00Z"

        const val SHORT_START = "2026-05-15T00:00:00"
        const val SHORT_END = "2026-05-20T00:00:00"
        const val DURING = "2026-05-16T12:00:00Z"
        const val AFTER_THE_END = "2026-05-22T00:00:00Z"
        const val AFTER_THE_END_PLUS_10_MIN = "2026-05-22T00:10:00Z"
    }
}
