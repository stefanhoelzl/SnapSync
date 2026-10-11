package app.snapsync.services.leave

import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.backend.LeaveNotifier
import app.snapsync.services.databases.SharedDatabase
import co.touchlab.kermit.Logger

/**
 * The leaves this device made that the backend has not confirmed yet — a leave made
 * offline still counts (decision record `changes/early-event-completion`, D3).
 *
 * A leave is instant for the member and works offline, so its request to the backend used to be fire-and-forget:
 * one attempt, never retried. That was harmless while an event lived its full lifetime regardless, but a finished
 * event is now deleted once every member has LEFT — so a leave the backend never heard of would keep the photos on
 * the server until the clock. Each leave is therefore RECORDED before it is sent, and every wake the app already gets
 * delivers what is still recorded, until the backend confirms each one.
 *
 * The `pendingLeave` rows of the shared database, beside the membership: they die with the install, which is right — a
 * deleted app is a device that never comes back, and the clock covers it.
 *
 * **Never raises.** Each change is one statement, so nothing is ever written over what could not be read. A record
 * that cannot be written is logged, and the leave that made it is still sent this once: at worst its event then waits
 * for its clock, which is where it stood before this existed.
 */
class PendingLeaves(
    private val db: SharedDatabase,
    private val notifier: LeaveNotifier,
    private val log: Logger = Logger.withTag("pendingLeaves"),
) {
    /**
     * Record [eventId] as left and not yet confirmed, with whether this device [received] every photo of the others —
     * replacing what an earlier record of it said. Call BEFORE the request, so a kill between the two loses nothing.
     */
    suspend fun record(eventId: String, received: Boolean = false) {
        runCatchingCancellable { db.use { it.pendingLeaveQueries.recordPendingLeave(eventId, received) } }
            .onFailure { log.w(it) { "the leave of $eventId was not recorded — sent this once, not retried" } }
    }

    /**
     * The backend leave of [eventId]: recorded first (replacing any earlier record of it with [received]), then every
     * outstanding leave is sent. One the backend does not confirm stays recorded, and the next wake sends it again — a
     * finished event is deleted once everyone has LEFT, so a lost leave is no longer harmless.
     */
    suspend fun leave(eventId: String, received: Boolean) {
        record(eventId, received)
        val outstanding = deliver(stored().orEmpty() + (eventId to received))
        if (outstanding > 0) log.i { "leave of $eventId not confirmed yet — $outstanding retried on the next wake" }
    }

    /**
     * Send every recorded leave; each the backend confirms is removed. One that fails stays for the next wake. Answers
     * how many are still outstanding.
     */
    suspend fun deliverAll(): Int = deliver(stored().orEmpty())

    /** The recorded leaves, for a test or the diagnostic dump. */
    suspend fun outstanding(): Set<String> = stored().orEmpty().keys

    /** Whether the recorded leave of [eventId] says it received everything; `null` when none is recorded. */
    suspend fun received(eventId: String): Boolean? = stored().orEmpty()[eventId]

    private suspend fun deliver(outstanding: Map<String, Boolean>): Int {
        if (outstanding.isEmpty()) return 0
        val delivered = outstanding.filter { (eventId, received) ->
            runCatchingCancellable { notifier.notifyLeaving(eventId, received).getOrThrow() }
                .onFailure { log.i { "leave of $eventId not confirmed yet ($it) — retried on the next wake" } }
                .isSuccess
        }
        // Only what was delivered AS SENT: a record replaced meanwhile is still owed.
        runCatchingCancellable {
            db.use { database ->
                database.transaction {
                    delivered.forEach { (eventId, received) ->
                        database.pendingLeaveQueries.confirmPendingLeave(eventId, received)
                    }
                }
            }
        }.onFailure { log.w(it) { "confirmed leaves not removed — a later wake confirms them again" } }
        // What is still recorded — a record replaced while its request was in flight included; when that cannot be
        // read, what this delivery could not confirm.
        return stored()?.size ?: (outstanding.size - delivered.size)
    }

    /** The recorded leaves, or `null` when they cannot be read now. */
    private fun stored(): Map<String, Boolean>? = runCatchingCancellable {
        db.use { it.pendingLeaveQueries.selectPendingLeaves().executeAsList().associate { row -> row.eventId to row.received } }
    }.getOrElse { null.also { _ -> log.w(it) { "the pending leaves are unreadable — left as they are" } } }
}
