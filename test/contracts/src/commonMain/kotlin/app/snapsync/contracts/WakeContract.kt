@file:OptIn(CompositionOnly::class, ExperimentalAtomicApi::class)

package app.snapsync.contracts

import app.snapsync.inbound.Completion
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Granted
import app.snapsync.inbound.InboundImplementation
import app.snapsync.inbound.Running
import app.snapsync.model.ScheduleResult
import app.snapsync.model.WakeCadence
import app.snapsync.model.WakeId
import app.snapsync.model.WakeNetwork
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeHandlers
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Where the system's queue of background wakes stands for this app when a clause starts. */
enum class WakeState {
    /** No heartbeat wake is pending. */
    EMPTY,

    /**
     * No wake is pending, and the binding can let the operating system deliver what is due and end a running wake
     * ([ScheduledWakes.os]).
     */
    DELIVERING,

    /** As [DELIVERING], on a platform with a library-change wake, which the binding can raise by changing the library. */
    DELIVERING_LIBRARY_CHANGES,

    /** On a platform that has no library-change wake (iOS). */
    NO_LIBRARY_WAKE,

    /** In a process the operating system refuses wake requests from (one whose bundle permits no task identifier). */
    REFUSING,
}

/** The operating system's side of a delivered wake, as a binding plays it on the real system. */
class WakeOs(
    /** Changes the member's photo library, as a new photo would. */
    val changeLibrary: suspend () -> Unit,
    /** Ends the wake now running, as the system does when its time is up. */
    val endRunningWake: suspend () -> Unit,
)

/**
 * The port as a clause receives it: [wake], which declares no reads, and [pendingWakes] — an observation handle over
 * the system's own queue (`docs/architecture.md`): how many heartbeat wake requests the operating system holds for
 * this app, over **every** task kind an adapter carries the heartbeat on. The state reached, never a record of which
 * call reached it.
 */
class ScheduledWakes(
    val wake: Wake,
    /** The system's side of a wake; required of a binding that reaches a delivering state. */
    val os: WakeOs? = null,
    val pendingWakes: suspend () -> Int,
)

/** One wake the adapter delivered: which, the completion it handed over with it, and whether the adapter expired it. */
private class Delivered(val id: WakeId, val completion: Completion) {
    val expired = AtomicReference(false)
}

/** Handlers that keep every wake delivered, releasing none — the clause decides when. */
private class WakeRecorder {
    private val delivered = AtomicReference<List<Delivered>>(emptyList())
    val all: List<Delivered> get() = delivered.load()

    @OptIn(InboundImplementation::class)
    val handlers = WakeHandlers(
        object : Granted<WakeId> {
            override fun handle(arg: WakeId, completion: Completion): Running {
                val wake = Delivered(arg, completion)
                while (true) {
                    val now = delivered.load()
                    if (delivered.compareAndSet(now, now + wake)) break
                }
                return object : Running {
                    override fun expire() = wake.expired.store(true)
                }
            }
        },
    )
}

/**
 * What every [Wake] promises the heartbeat (`docs/architecture.md` — this list IS the specification). The heartbeat is
 * re-armed after every tail that leaves work and on every heartbeat wake; a port that stacked a request per call would
 * flood the system's queue, and one that cancelled nothing would keep waking a device that left its event.
 *
 * Its recorded name is the port's old one, `BackgroundScheduler`: the adapter's operating-system calls did not change
 * when the port became [Wake] (phase 11f), so the device recording replays unedited — as 11e kept `LinkOpener`.
 * A platform's answer for a wake it does not have ([ScheduleResult.Unsupported]) makes no operating-system call to
 * record, so its clause has a state of its own, [WakeState.NO_LIBRARY_WAKE], which the recorded host never reaches.
 */
object WakeContract : Contract<WakeState, ScheduledWakes>("BackgroundScheduler") {

    /** The heartbeat's busy trigger, as the `Heartbeat` service asks for it. */
    private val HEARTBEAT = WakeTrigger.After(earliest = 60.seconds, network = WakeNetwork.ANY)

    /**
     * The heartbeat's idle trigger. An adapter may carry it on another task kind than [HEARTBEAT] (iOS: an app refresh
     * beside the processing task), so the two must still replace each other: one timed wake pending at a time, or an
     * idle re-arm leaves the busy wake standing and the cadence never drops.
     */
    private val IDLE = WakeTrigger.After(earliest = 1.hours, network = WakeNetwork.ANY, cadence = WakeCadence.IDLE)

    /** A wake due at once. */
    private val NOW = WakeTrigger.After(earliest = Duration.ZERO, network = WakeNetwork.ANY)

    override val clauses = clauses {

        clause(
            "DELIVERING_A_DUE_WAKE_IS_DELIVERED_AND_RELEASED",
            WakeState.DELIVERING,
            covers = cells {
                on<Wake> {
                    answers(Wake::inbound).returns()
                    answers(Wake::schedule).with(ScheduleResult.Scheduled::class)
                    calls(WakeHandlers::onWake, WakeId.Heartbeat, Completion::class)
                    handle<Completion>().answers(Completion::complete).returns()
                }
            },
        ) { subject ->
            val recorder = WakeRecorder()
            subject.wake.inbound(recorder.handlers)
            assertEquals(ScheduleResult.Scheduled, subject.wake.schedule(WakeId.Heartbeat, NOW))
            awaitWithin { recorder.all.isNotEmpty() }
            val wake = recorder.all.single()
            assertEquals(WakeId.Heartbeat, wake.id, "the wake is delivered under the id it was asked for")
            wake.completion.complete()
            awaitWithin { subject.pendingWakes() == 0 }
        }

        clause(
            "DELIVERING_LIBRARY_CHANGES_A_LIBRARY_CHANGE_WAKES_THE_APP",
            WakeState.DELIVERING_LIBRARY_CHANGES,
            covers = cells {
                on<Wake> {
                    answers(Wake::schedule).with(ScheduleResult.Scheduled::class)
                    calls(WakeHandlers::onWake, WakeId.LibraryChanged, Completion::class)
                }
            },
        ) { subject ->
            val os = assertNotNull(subject.os, "a binding that delivers wakes plays the system's side of them")
            val recorder = WakeRecorder()
            subject.wake.inbound(recorder.handlers)
            assertEquals(
                ScheduleResult.Scheduled,
                subject.wake.schedule(WakeId.LibraryChanged, WakeTrigger.LibraryChange(maxDelay = 1.seconds)),
            )
            os.changeLibrary()
            awaitWithin { recorder.all.any { it.id == WakeId.LibraryChanged } }
            recorder.all.forEach { it.completion.complete() }
        }

        clause(
            "DELIVERING_A_WAKE_THE_SYSTEM_ENDS_RUNS_ITS_EXPIRY",
            WakeState.DELIVERING,
            covers = cells {
                on<Wake> {
                    calls(WakeHandlers::onWake, WakeId.Heartbeat, Completion::class)
                    handle<Running>().answers(Running::expire).returns()
                }
            },
        ) { subject ->
            val os = assertNotNull(subject.os, "a binding that delivers wakes plays the system's side of them")
            val recorder = WakeRecorder()
            subject.wake.inbound(recorder.handlers)
            subject.wake.schedule(WakeId.Heartbeat, NOW)
            awaitWithin { recorder.all.isNotEmpty() }
            val wake = recorder.all.single()
            assertFalse(wake.expired.load(), "a running wake has not expired")
            os.endRunningWake()
            awaitWithin { wake.expired.load() }
            wake.completion.complete()
        }

        clause(
            "NO_LIBRARY_WAKE_IS_UNSUPPORTED",
            WakeState.NO_LIBRARY_WAKE,
            covers = cells {
                on<Wake>().answers(Wake::schedule).with(ScheduleResult.Unsupported::class)
            },
        ) { subject ->
            assertIs<ScheduleResult.Unsupported>(
                subject.wake.schedule(WakeId.LibraryChanged, WakeTrigger.LibraryChange(maxDelay = 1.seconds)),
                "a wake the platform does not have is answered so, and asks the system nothing",
            )
        }

        clause(
            "REFUSING_A_REFUSED_REQUEST_IS_REFUSED",
            WakeState.REFUSING,
            covers = cells {
                on<Wake>().answers(Wake::schedule).with(ScheduleResult.Refused::class)
            },
        ) { subject ->
            assertIs<ScheduleResult.Refused>(
                subject.wake.schedule(WakeId.Heartbeat, HEARTBEAT),
                "a request the system refused is not a scheduled wake",
            )
        }

        clause(
            "SCHEDULE_ARMS_ONE",
            WakeState.EMPTY,
            covers = cells {
                on<Wake>().answers(Wake::schedule).with(ScheduleResult.Scheduled::class)
            },
        ) { subject ->
            assertEquals(ScheduleResult.Scheduled, subject.wake.schedule(WakeId.Heartbeat, HEARTBEAT))
            assertEquals(1, subject.pendingWakes(), "one request makes one pending wake")
        }

        clause(
            "SCHEDULE_IS_IDEMPOTENT",
            WakeState.EMPTY,
            covers = cells {
                on<Wake>().answers(Wake::schedule).with(ScheduleResult.Scheduled::class)
            },
        ) { subject ->
            subject.wake.schedule(WakeId.Heartbeat, HEARTBEAT)
            subject.wake.schedule(WakeId.Heartbeat, HEARTBEAT)
            assertEquals(1, subject.pendingWakes(), "a repeated request replaces the pending one, never stacks")
        }

        clause(
            "CANCEL_CLEARS",
            WakeState.EMPTY,
            covers = cells {
                on<Wake> {
                    answers(Wake::schedule).with(ScheduleResult.Scheduled::class)
                    answers(Wake::cancel).returns()
                }
            },
        ) { subject ->
            subject.wake.schedule(WakeId.Heartbeat, HEARTBEAT)
            subject.wake.cancel(WakeId.Heartbeat)
            assertEquals(0, subject.pendingWakes(), "a cancel leaves no pending wake")
        }

        clause(
            "CANCEL_EMPTY_IS_QUIET",
            WakeState.EMPTY,
            covers = cells {
                on<Wake>().answers(Wake::cancel).returns()
            },
        ) { subject ->
            subject.wake.cancel(WakeId.Heartbeat)
            assertEquals(0, subject.pendingWakes(), "cancelling nothing is not a failure, and arms nothing")
        }

        clause(
            "IDLE_ARMS_ONE",
            WakeState.EMPTY,
            covers = cells {
                on<Wake>().answers(Wake::schedule).with(ScheduleResult.Scheduled::class)
            },
        ) { subject ->
            assertEquals(ScheduleResult.Scheduled, subject.wake.schedule(WakeId.Heartbeat, IDLE))
            assertEquals(1, subject.pendingWakes(), "an idle request makes one pending wake")
        }

        clause(
            "IDLE_REPLACES_BUSY",
            WakeState.EMPTY,
            covers = cells {
                on<Wake>().answers(Wake::schedule).with(ScheduleResult.Scheduled::class)
            },
        ) { subject ->
            subject.wake.schedule(WakeId.Heartbeat, HEARTBEAT)
            subject.wake.schedule(WakeId.Heartbeat, IDLE)
            assertEquals(1, subject.pendingWakes(), "going idle withdraws the busy wake, never keeps both")
        }

        clause(
            "BUSY_REPLACES_IDLE",
            WakeState.EMPTY,
            covers = cells {
                on<Wake>().answers(Wake::schedule).with(ScheduleResult.Scheduled::class)
            },
        ) { subject ->
            subject.wake.schedule(WakeId.Heartbeat, IDLE)
            subject.wake.schedule(WakeId.Heartbeat, HEARTBEAT)
            assertEquals(1, subject.pendingWakes(), "going busy withdraws the idle wake, never keeps both")
        }

        clause(
            "CANCEL_CLEARS_IDLE",
            WakeState.EMPTY,
            covers = cells {
                on<Wake> {
                    answers(Wake::schedule).with(ScheduleResult.Scheduled::class)
                    answers(Wake::cancel).returns()
                }
            },
        ) { subject ->
            subject.wake.schedule(WakeId.Heartbeat, IDLE)
            subject.wake.cancel(WakeId.Heartbeat)
            assertEquals(0, subject.pendingWakes(), "a cancel withdraws the idle wake too")
        }
    }
}
