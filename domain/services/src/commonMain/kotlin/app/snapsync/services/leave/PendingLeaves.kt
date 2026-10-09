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
 * The leaves this device made that the backend has not confirmed yet — a leave made
 * offline still counts (decision record `changes/early-event-completion`, D3).
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
 * **Never raises, and never writes over what it could not read.** Only an absent record is empty. One that exists but
 * cannot be read now is left as it is, because writing over it would drop every leave it holds: a leave recorded
 * meanwhile is held in memory, sent with the rest, and written into the record at its next readable moment. A refused
 * write is logged: at worst that change is lost and its event waits for its clock, which is where it stood before this
 * existed.
 */
class PendingLeaves(
    private val files: Files,
    private val notifier: LeaveNotifier,
    private val log: Logger = Logger.withTag("pendingLeaves"),
) {
    /** Serializes the read-modify-write of the record — a leave and a wake's delivery can run at once. */
    private val mutex = Mutex()

    /** Leaves recorded while the record was unreadable — not on disk yet, so this process still owes them. */
    private val unwritten = mutableMapOf<String, Boolean>()

    /**
     * Record [eventId] as left and not yet confirmed, with whether this device [received] every photo of the others —
     * replacing what an earlier record of it said. Call BEFORE the request, so a kill between the two loses nothing.
     */
    suspend fun record(eventId: String, received: Boolean = false) = mutex.withLock {
        unwritten[eventId] = received
        read()?.let { rewrite(it) }
    }

    /**
     * Send every recorded leave; each the backend confirms is removed. One that fails stays for the next wake. Answers
     * how many are still outstanding.
     */
    /**
     * The backend leave of [eventId]: recorded first (replacing any earlier record of it with [received]), then every
     * outstanding leave is sent. One the backend does not confirm stays recorded, and the next wake sends it again — a
     * finished event is deleted once everyone has LEFT, so a lost leave is no longer harmless.
     */
    suspend fun leave(eventId: String, received: Boolean) {
        record(eventId, received)
        val outstanding = deliverAll()
        if (outstanding > 0) log.i { "leave of $eventId not confirmed yet — $outstanding retried on the next wake" }
    }

    suspend fun deliverAll(): Int {
        val outstanding = mutex.withLock { read().orEmpty() + unwritten }
        if (outstanding.isEmpty()) return 0
        val delivered = outstanding.filter { (eventId, received) ->
            runCatchingCancellable { notifier.notifyLeaving(eventId, received).getOrThrow() }
                .onFailure { log.i { "leave of $eventId not confirmed yet ($it) — retried on the next wake" } }
                .isSuccess
        }
        return mutex.withLock {
            // Only what was delivered AS SENT: a record replaced meanwhile is still owed.
            delivered.forEach { (eventId, received) -> if (unwritten[eventId] == received) unwritten.remove(eventId) }
            // Unreadable now: the record keeps the delivered ones too, and a later wake's delivery confirms them again.
            val stored = read() ?: return@withLock (outstanding - delivered.keys).size
            rewrite(stored.filterNot { (eventId, received) -> delivered[eventId] == received }).size
        }
    }

    /** The recorded leaves, for a test or the diagnostic dump. */
    suspend fun outstanding(): Set<String> = mutex.withLock { (read().orEmpty() + unwritten).keys }

    /** Whether the recorded leave of [eventId] says it received everything; `null` when none is recorded. */
    suspend fun received(eventId: String): Boolean? = mutex.withLock { (read().orEmpty() + unwritten)[eventId] }

    /** The recorded leaves: empty when there is no record, `null` when one may exist but cannot be read now. */
    private fun read(): Map<String, Boolean>? = when (val r = files.read(FileArea.SHARED, PATH)) {
        is FileResult.Ok -> r.value.decodeToString().lines().map(String::trim).filter { it.isNotEmpty() }
            .associate { line -> line.substringBefore(' ') to (line.substringAfter(' ', "") == RECEIVED) }
        FileResult.NotFound -> emptyMap()
        else -> null.also { log.w { "the pending-leave record is unreadable ($r) — left as it is" } }
    }

    /** Write [stored] with the [unwritten] leaves folded in; answers what the record now holds. */
    private fun rewrite(stored: Map<String, Boolean>): Map<String, Boolean> {
        val leaves = stored + unwritten
        val text = leaves.entries.sortedBy { it.key }
            .map { (eventId, received) -> if (received) "$eventId $RECEIVED" else eventId }
        val written = files.write(FileArea.SHARED, PATH, text.joinToString("\n").encodeToByteArray())
        if (written is FileResult.Ok) {
            unwritten.clear()
        } else {
            log.w { "the pending-leave record was not written ($written)" }
        }
        return leaves
    }

    private companion object {
        /** Runtime identity: installed devices hold their record at this path. */
        const val PATH = "membership/pending-leaves.txt"

        /** The marker of a leave that received everything; a line without it — every older record — did not. */
        const val RECEIVED = "received"
    }
}
