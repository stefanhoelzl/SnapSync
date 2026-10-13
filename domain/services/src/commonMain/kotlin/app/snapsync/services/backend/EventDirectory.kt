package app.snapsync.services.backend

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.time.Timestamp
import app.snapsync.model.EventCompletionState
import app.snapsync.model.EventLookup
import app.snapsync.model.MemberCounts
import app.snapsync.model.TimestampText
import app.snapsync.wire.api.EventNotFound
import app.snapsync.wire.api.GetEventReply
import app.snapsync.wire.api.Routes

/**
 * Fetches an event's details by id for the join confirmation gate. Non-throwing: a transport/parse
 * error maps to [EventLookup.Failed], never an exception (the gate reduces it to a retryable state).
 */
fun interface EventDirectory {
    suspend fun fetch(eventId: EventId): EventLookup
}

/**
 * [EventDirectory] over the backend's event read (`getEvent`): a found event with a **non-blank name** and a **readable
 * `startsAt`, `endsAt`, AND `deletesAt`** → [EventLookup.Found]; one whose name is empty or whitespace-only, or whose
 * dates do not read — or an answer that does not decode at all — → [EventLookup.Failed] (malformed/transient,
 * retryable); [EventNotFound] → [EventLookup.NotFound]; any other answer → [EventLookup.Failed].
 *
 * The `404` ↔ `Failed` split is load-bearing beyond the join gate: it is the ONLY place "the event is
 * definitively gone" is separated from "I could not tell", and a membership is destroyed on the
 * former. Every ambiguous outcome must keep landing on [EventLookup.Failed].
 */
class BackendEventDirectory(private val gateway: BackendGateway) : EventDirectory {

    override suspend fun fetch(eventId: EventId): EventLookup = when (
        val reply = gateway.send(
            Routes.GetEvent.request(eventId.value),
        )
    ) {
        is GetEventReply.Found -> {
            val meta = reply.eventDetails
            // All four are required: a found event missing any is malformed → retryable Failed. Never a nameless
            // Found (the event-album title needs a name), never a Found with an invented floor (that would silently
            // LOWER it), and never one with an invented deadline (that would decide whether a membership is
            // destroyed).
            //
            // ⚠️ The name is rejected when BLANK, and this check is the ONLY guard against a blank one entering a
            // membership: `EventConfig` requires the name KEY, not a non-blank VALUE (so `{"name":""}` decodes), and the
            // album coordinator's former empty-name clause — the one place a blank title became a permanent artifact —
            // is gone. Nothing downstream re-checks. Decision record: `changes/archive/…-remove-nameless-config-fallback`.
            val name = meta.name.takeIf { it.isNotBlank() }
            val startsAt = canonicalOrNull(meta.startsAt)
            val endsAt = canonicalOrNull(meta.endsAt)
            val deletesAt = canonicalOrNull(meta.deletesAt)
            if (name != null && startsAt != null && endsAt != null && deletesAt != null) {
                EventLookup.Found(
                    name = name,
                    startsAt = EventStart(startsAt),
                    endsAt = EventEnd(endsAt),
                    deletesAt = DeletesAt(deletesAt),
                    // Presence is the fact; the instant is the backend's record. `closedAt` is read by builds that
                    // predate open-until dates only (`PENDING_CLEANUPS.md`): an event no longer closes for this one.
                    completion = EventCompletionState(
                        completed = meta.completedAt != null,
                        members = MemberCounts(meta.members.active, meta.members.`final`, meta.members.waiting),
                    ),
                    keyId = meta.keyId,
                    // Not one of the required four: an unreadable one costs only the "guests can join until" line.
                    openUntil = canonicalOrNull(meta.openUntil)?.let(::OpenUntil),
                )
            } else {
                EventLookup.Failed
            }
        }
        EventNotFound -> EventLookup.NotFound
        else -> EventLookup.Failed
    }

    /**
     * A fetched date as a capture date, or `null` when it names no instant. Truncated to its second toward the earlier
     * instant, the inclusive direction — so a photo taken within the date's own second is admitted rather than lost.
     */
    private fun canonicalOrNull(raw: String): Timestamp? = TimestampText.parseOrNull(raw)
}
