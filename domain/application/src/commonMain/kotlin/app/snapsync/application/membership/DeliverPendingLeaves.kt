package app.snapsync.application.membership

import app.snapsync.application.EntryPointCall
import app.snapsync.data.membership.LeaveFact
import app.snapsync.data.membership.LeaveNotifier
import app.snapsync.data.membership.PendingLeaves
import app.snapsync.data.membership.PendingLeavesRead
import app.snapsync.domain.event.EventId

/**
 * Tell the backend every leave it has not confirmed — a leave made offline still counts, and an event is deleted once
 * everyone left. Each confirmed leave is owed no more; one that is not stays for the next wake.
 */
class DeliverPendingLeaves(private val pending: PendingLeaves, private val notifier: LeaveNotifier) {
    @EntryPointCall
    suspend operator fun invoke(): DeliveryOutcome = when (val owed = pending.outstanding()) {
        is PendingLeavesRead.Ok -> owed.events.count { delivered(it) }.let {
            DeliveryOutcome.Delivered(confirmed = it, outstanding = owed.events.size - it)
        }
        is PendingLeavesRead.Unavailable -> DeliveryOutcome.Unreadable
    }

    private suspend fun delivered(event: EventId): Boolean = when (notifier.leave(event)) {
        // A confirmation that cannot be written is sent again next wake, which the backend answers the same.
        LeaveFact.Confirmed -> true.also { pending.confirm(event) }
        LeaveFact.Unanswered -> false
    }
}

/** What a delivery of the owed leaves came to. */
sealed interface DeliveryOutcome {
    /** [confirmed] leaves are owed no more; [outstanding] still are. */
    data class Delivered(val confirmed: Int, val outstanding: Int) : DeliveryOutcome

    /** The owed leaves could not be read: they wait for the next wake. */
    data object Unreadable : DeliveryOutcome
}
