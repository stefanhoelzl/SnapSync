package app.snapsync.data.membership

import app.snapsync.data.membership.MembershipRows.membership
import app.snapsync.data.membership.MembershipRows.stored
import app.snapsync.data.store.Stored
import app.snapsync.data.store.db.LedgerDatabase
import app.snapsync.domain.event.EventId
import app.snapsync.domain.membership.LeftMembership
import app.snapsync.domain.membership.MintsLeftMembership

// How a membership ends, inside the database: each a transaction's body MembershipRepository runs, so the row, the
// leave owed and the event's check times go together or not at all.

// A row whose event id spells none cannot owe the backend a leave: the membership is unreadable, and nothing changes.

object MembershipEnds {
    /** End the membership held, owe its leave and forget its checks. */
    internal fun LedgerDatabase.leave(): LeaveResult =
        membershipQueries.selectMembership().executeAsList().map { leaving(it.eventId) }.firstOrNull()
            ?: LeaveResult.NotJoined

    private fun LedgerDatabase.leaving(stored: Stored<EventId>): LeaveResult = when (stored) {
        is Stored.Valid -> left(stored)
        Stored.Empty -> LeaveResult.Failed("the stored membership has no event id")
    }

    @OptIn(MintsLeftMembership::class)
    private fun LedgerDatabase.left(event: Stored.Valid<EventId>): LeaveResult =
        LeaveResult.Left(LeftMembership(event.value)).also {
            membershipQueries.deleteMembership(event)
            pendingLeaveQueries.recordPendingLeave(event)
            eventCheckQueries.clearChecks(event)
        }
}
