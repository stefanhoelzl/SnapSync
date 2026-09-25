package app.snapsync.contracts

import app.snapsync.ports.BackgroundScheduler
import kotlin.test.assertEquals

/** Where the system's queue of background wakes stands for this app when a clause starts. */
enum class BackgroundSchedulerState {
    /** No wake is pending for the scheduler's identifier. */
    EMPTY,
}

/**
 * The scheduler as a clause receives it: the port, which declares no reads, and [pendingWakes] — an observation
 * handle over the system's own queue (`docs/architecture.md`): how many wake requests the operating system
 * holds for the scheduler's identifier. The state reached, never a record of which call reached it.
 */
class ScheduledWakes(
    val scheduler: BackgroundScheduler,
    val pendingWakes: suspend () -> Int,
)

/**
 * What every [BackgroundScheduler] promises the upload pump (`docs/architecture.md` — this list IS the
 * specification). The pump re-arms after every cycle that leaves work, and on every heartbeat; a scheduler that
 * stacked a request per call would flood the system's queue, and one that cancelled nothing would keep waking a
 * device that left its event.
 */
object BackgroundSchedulerContract : Contract<BackgroundSchedulerState, ScheduledWakes>("BackgroundScheduler") {

    override val clauses = clauses {

        clause("SCHEDULE_ARMS_ONE", BackgroundSchedulerState.EMPTY) { subject ->
            subject.scheduler.scheduleNext()
            assertEquals(1, subject.pendingWakes(), "one request makes one pending wake")
        }

        clause("SCHEDULE_IS_IDEMPOTENT", BackgroundSchedulerState.EMPTY) { subject ->
            subject.scheduler.scheduleNext()
            subject.scheduler.scheduleNext()
            assertEquals(1, subject.pendingWakes(), "a repeated request replaces the pending one, never stacks")
        }

        clause("CANCEL_CLEARS", BackgroundSchedulerState.EMPTY) { subject ->
            subject.scheduler.scheduleNext()
            subject.scheduler.cancel()
            assertEquals(0, subject.pendingWakes(), "a cancel leaves no pending wake")
        }

        clause("CANCEL_EMPTY_IS_QUIET", BackgroundSchedulerState.EMPTY) { subject ->
            subject.scheduler.cancel()
            assertEquals(0, subject.pendingWakes(), "cancelling nothing is not a failure, and arms nothing")
        }
    }
}
