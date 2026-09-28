package app.snapsync.services.leave

import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.Files
import app.snapsync.services.backend.LeaveNotifier
import co.touchlab.kermit.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The leaves this device made that the backend has not confirmed yet (capability `event-lifetime`, "A leave made
 * offline still counts"; decision record `changes/early-event-completion`, D3).
 *
 * A leave is instant for the member and works offline, so its request to the backend used to be fire-and-forget:
 * one attempt, never retried. That was harmless while an event lived its full lifetime regardless, but a finished
 * event is now deleted once every member has LEFT — so a leave the backend never heard of would keep the photos on
 * the server until the clock. Each leave is therefore RECORDED before it is sent, and every wake the app already gets
 * delivers what is still recorded, until the backend confirms each one.
 *
 * One file in the shared area, beside the membership: it dies with the install, which is right — a deleted app is a
 * device that never comes back, and the clock covers it.
 *
 * **Never raises.** An unreadable record reads as empty, and a refused write is logged: at worst one leave is not
 * retried and the event waits for its clock, which is where it stood before this existed.
 */
class PendingLeaves(
    private val files: Files,
    private val notifier: LeaveNotifier,
    private val log: Logger = Logger.withTag("pendingLeaves"),
) {
    /** Serializes the read-modify-write of the record — a leave and a wake's delivery can run at once. */
    private val mutex = Mutex()

    /** Record [eventId] as left and not yet confirmed. Call BEFORE the request, so a kill between the two loses nothing. */
    suspend fun record(eventId: String) = mutex.withLock { write(read() + eventId) }

    /**
     * Send every recorded leave; each the backend confirms is removed. One that fails stays for the next wake. Answers
     * how many are still outstanding.
     */
    suspend fun deliverAll(): Int {
        val outstanding = mutex.withLock { read() }
        if (outstanding.isEmpty()) return 0
        val delivered = outstanding.filterTo(mutableSetOf()) { eventId ->
            runCatchingCancellable { notifier.notifyLeaving(eventId).getOrThrow() }
                .onFailure { log.i { "leave of $eventId not confirmed yet ($it) — retried on the next wake" } }
                .isSuccess
        }
        return mutex.withLock {
            val remaining = read() - delivered
            write(remaining)
            remaining.size
        }
    }

    /** The recorded leaves, for a test or the diagnostic dump. */
    suspend fun outstanding(): Set<String> = mutex.withLock { read() }

    private fun read(): Set<String> = when (val r = files.read(FileArea.SHARED, PATH)) {
        is FileResult.Ok -> r.value.decodeToString().lines().map(String::trim).filterTo(mutableSetOf()) { it.isNotEmpty() }
        else -> emptySet()
    }

    private fun write(ids: Set<String>) {
        val written = files.write(FileArea.SHARED, PATH, ids.sorted().joinToString("\n").encodeToByteArray())
        if (written !is FileResult.Ok) log.w { "the pending-leave record was not written ($written)" }
    }

    private companion object {
        /** Runtime identity: installed devices hold their record at this path. */
        const val PATH = "membership/pending-leaves.txt"
    }
}
