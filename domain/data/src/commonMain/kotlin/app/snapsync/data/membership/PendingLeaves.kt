package app.snapsync.data.membership

import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.StoreWrite
import app.snapsync.domain.event.EventId
import app.snapsync.ports.DbResult

/**
 * The leaves this device made that the backend has not confirmed yet — a leave made offline still counts, and a
 * finished event is deleted once every member has left. Each is recorded by the leave itself, in the transaction that
 * ends the membership ([MembershipRepository.leave]), and stays until the backend confirms it. They die with the
 * install, which is right: a deleted app is a device that never comes back, and the event's clock covers it.
 */
class PendingLeaves(private val db: SharedDatabase) {

    /** The leaves still owed. */
    fun outstanding(): PendingLeavesRead =
        when (val read = db.execute { it.pendingLeaveQueries.selectPendingLeaves().executeAsList().toSet() }) {
            is DbResult.Ok -> PendingLeavesRead.Ok(read.value)
            is DbResult.Failed -> PendingLeavesRead.Unavailable(read.detail)
        }

    /** The backend confirmed the leave of [event]: it is owed no more. */
    fun confirm(
        event: EventId,
    ): StoreWrite = when (val written = db.execute { it.pendingLeaveQueries.confirmPendingLeave(event) }) {
        is DbResult.Ok -> StoreWrite.Written
        is DbResult.Failed -> StoreWrite.Failed(written.detail)
    }
}

/** The leaves still owed to the backend, or why they could not be read. */
sealed interface PendingLeavesRead {
    data class Ok(val events: Set<EventId>) : PendingLeavesRead

    data class Unavailable(val detail: String) : PendingLeavesRead
}
