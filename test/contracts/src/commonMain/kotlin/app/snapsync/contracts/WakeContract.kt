package app.snapsync.contracts

import app.snapsync.model.ScheduleResult
import app.snapsync.model.WakeCadence
import app.snapsync.model.WakeId
import app.snapsync.model.WakeNetwork
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.Wake
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Where the system's queue of background wakes stands for this app when a clause starts. */
enum class WakeState {
    /** No heartbeat wake is pending. */
    EMPTY,
}

/**
 * The port as a clause receives it: [wake], which declares no reads, and [pendingWakes] — an observation handle over
 * the system's own queue (`docs/architecture.md`): how many heartbeat wake requests the operating system holds for
 * this app, over **every** task kind an adapter carries the heartbeat on. The state reached, never a record of which
 * call reached it.
 */
class ScheduledWakes(
    val wake: Wake,
    val pendingWakes: suspend () -> Int,
)

/**
 * What every [Wake] promises the heartbeat (`docs/architecture.md` — this list IS the specification). The heartbeat is
 * re-armed after every tail that leaves work and on every heartbeat wake; a port that stacked a request per call would
 * flood the system's queue, and one that cancelled nothing would keep waking a device that left its event.
 *
 * Its recorded name is the port's old one, `BackgroundScheduler`: the adapter's operating-system calls did not change
 * when the port became [Wake] (phase 11f), so the device recording replays unedited — as 11e kept `LinkOpener`.
 * A platform's answer for a wake it does not have ([ScheduleResult.Unsupported]) is adapter vocabulary, pinned beside
 * each adapter rather than here: it makes no operating-system call to record.
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

    override val clauses = clauses {

        clause("SCHEDULE_ARMS_ONE", WakeState.EMPTY) { subject ->
            assertEquals(ScheduleResult.Scheduled, subject.wake.schedule(WakeId.Heartbeat, HEARTBEAT))
            assertEquals(1, subject.pendingWakes(), "one request makes one pending wake")
        }

        clause("SCHEDULE_IS_IDEMPOTENT", WakeState.EMPTY) { subject ->
            subject.wake.schedule(WakeId.Heartbeat, HEARTBEAT)
            subject.wake.schedule(WakeId.Heartbeat, HEARTBEAT)
            assertEquals(1, subject.pendingWakes(), "a repeated request replaces the pending one, never stacks")
        }

        clause("CANCEL_CLEARS", WakeState.EMPTY) { subject ->
            subject.wake.schedule(WakeId.Heartbeat, HEARTBEAT)
            subject.wake.cancel(WakeId.Heartbeat)
            assertEquals(0, subject.pendingWakes(), "a cancel leaves no pending wake")
        }

        clause("CANCEL_EMPTY_IS_QUIET", WakeState.EMPTY) { subject ->
            subject.wake.cancel(WakeId.Heartbeat)
            assertEquals(0, subject.pendingWakes(), "cancelling nothing is not a failure, and arms nothing")
        }

        clause("IDLE_ARMS_ONE", WakeState.EMPTY) { subject ->
            assertEquals(ScheduleResult.Scheduled, subject.wake.schedule(WakeId.Heartbeat, IDLE))
            assertEquals(1, subject.pendingWakes(), "an idle request makes one pending wake")
        }

        clause("IDLE_REPLACES_BUSY", WakeState.EMPTY) { subject ->
            subject.wake.schedule(WakeId.Heartbeat, HEARTBEAT)
            subject.wake.schedule(WakeId.Heartbeat, IDLE)
            assertEquals(1, subject.pendingWakes(), "going idle withdraws the busy wake, never keeps both")
        }

        clause("BUSY_REPLACES_IDLE", WakeState.EMPTY) { subject ->
            subject.wake.schedule(WakeId.Heartbeat, IDLE)
            subject.wake.schedule(WakeId.Heartbeat, HEARTBEAT)
            assertEquals(1, subject.pendingWakes(), "going busy withdraws the idle wake, never keeps both")
        }

        clause("CANCEL_CLEARS_IDLE", WakeState.EMPTY) { subject ->
            subject.wake.schedule(WakeId.Heartbeat, IDLE)
            subject.wake.cancel(WakeId.Heartbeat)
            assertEquals(0, subject.pendingWakes(), "a cancel withdraws the idle wake too")
        }
    }
}
