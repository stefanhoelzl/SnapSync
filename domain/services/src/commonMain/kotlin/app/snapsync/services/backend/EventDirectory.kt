package app.snapsync.services.backend

import app.snapsync.model.CaptureDate
import app.snapsync.model.DeletesAt
import app.snapsync.model.EventEnd
import app.snapsync.model.EventLookup
import app.snapsync.model.EventStart
import app.snapsync.model.Reply
import app.snapsync.model.instantToCutoff
import app.snapsync.model.runCatchingCancellable
import kotlin.time.Instant

/**
 * Fetches an event's details by id for the join confirmation gate. Non-throwing: a transport/parse
 * error maps to [EventLookup.Failed], never an exception (the gate reduces it to a retryable state).
 */
fun interface EventDirectory {
    suspend fun fetch(eventId: String): EventLookup
}

/**
 * [EventDirectory] over the backend's public event read: a served read with a **non-blank name** and a **readable
 * `startsAt`, `endsAt`, AND `deletesAt`** → [EventLookup.Found]; a served read missing **any** — or carrying an
 * empty/whitespace-only name — → [EventLookup.Failed] (malformed/transient, retryable); `404` →
 * [EventLookup.NotFound]; any other answer → [EventLookup.Failed].
 *
 * The `404` ↔ `Failed` split is load-bearing beyond the join gate: it is the ONLY place "the event is
 * definitively gone" is separated from "I could not tell", and a membership is destroyed (capability
 * `manage-membership`) on the former. Every ambiguous outcome must keep landing on [EventLookup.Failed].
 */
class BackendEventDirectory(private val backend: AuthenticatedBackend) : EventDirectory {

    override suspend fun fetch(eventId: String): EventLookup = when (val reply = backend.getEvent(eventId)) {
        is Reply.Ok -> {
            val meta = reply.value
            // All four are required: a served read missing any is malformed → retryable Failed. Never a nameless
            // Found (the event-album title needs a name), never a Found with an invented floor (that would silently
            // LOWER it), and never one with an invented deadline (that would decide whether a membership is
            // destroyed).
            //
            // ⚠️ The name is rejected when BLANK, not merely when absent, and this check is the ONLY guard against a
            // blank one entering a membership: `EventConfig` requires the name KEY, not a non-blank VALUE (so
            // `{"name":""}` decodes), and the album coordinator's former empty-name clause — the one place a blank
            // title became a permanent artifact — is gone. Nothing downstream re-checks.
            // Decision record: `changes/archive/…-remove-nameless-config-fallback`.
            val name = meta.name?.takeIf { it.isNotBlank() }
            val startsAt = meta.startsAt?.let(::canonicalOrNull)
            val endsAt = meta.endsAt?.let(::canonicalOrNull)
            val deletesAt = meta.deletesAt?.let(::canonicalOrNull)
            if (name != null && startsAt != null && endsAt != null && deletesAt != null) {
                EventLookup.Found(
                    name = name,
                    startsAt = EventStart(startsAt),
                    endsAt = EventEnd(endsAt),
                    deletesAt = DeletesAt(deletesAt),
                )
            } else {
                EventLookup.Failed
            }
        }
        is Reply.Refused -> if (reply.status == NOT_FOUND) EventLookup.NotFound else EventLookup.Failed
        is Reply.Malformed, is Reply.Unreachable -> EventLookup.Failed
    }

    /**
     * Normalize a fetched instant into the canonical cutoff shape, or `null` when it does not parse.
     *
     * This is the boundary that makes [EventLookup.Found.startsAt] canonical **by construction**, and it is not
     * ceremony. The backend guarantees the shape for events created *after* start dates existed — but for a
     * **legacy** marker it synthesizes `startsAt` from `createdAt`, which `toISOString()` mints with MILLISECONDS.
     * An off-shape floor is quietly poisonous downstream: the clamp is a *lexicographic* `maxOf`, so
     * `…T00:00:00.182Z` sorts before `…T00:00:00Z`; and were such a value to win the clamp it would be persisted as
     * the cutoff, which the iOS walk parses with a bare `NSISO8601DateFormatter` that REJECTS a fractional second —
     * silently costing the bounded PhotoKit fetch.
     *
     * `instantToCutoff` truncates toward the earlier instant (dropping the fraction), the inclusive direction — so
     * a photo taken within the cutoff's own second is admitted rather than lost.
     */
    private fun canonicalOrNull(raw: String): CaptureDate? =
        runCatchingCancellable { instantToCutoff(Instant.parse(raw)) }.getOrNull()

    private companion object {
        const val NOT_FOUND = 404
    }
}
