package app.snapsync.data.event

import app.snapsync.data.Parsed
import app.snapsync.data.Parsed.Companion.instant
import app.snapsync.data.Parsed.Companion.then
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ServiceFaults
import app.snapsync.data.crypto.MetaCipher
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.Event
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventLookupFact
import app.snapsync.domain.event.EventName
import app.snapsync.domain.event.EventRange
import app.snapsync.domain.event.EventRangeVerdict
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.IdentifiedKey
import app.snapsync.domain.event.MemberCounts
import app.snapsync.domain.event.NameVerdict
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.time.Timestamp
import app.snapsync.format.EncryptionText
import app.snapsync.format.TimestampText
import app.snapsync.wire.api.ClientError
import app.snapsync.wire.api.EventNotFound
import app.snapsync.wire.api.GetEventReply
import app.snapsync.wire.api.Maintenance
import app.snapsync.wire.api.NoAnswer
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.ServerIssue
import app.snapsync.wire.models.EventDetails

/**
 * The server's word about an event (`getEvent`): found, with every fact a membership keeps; found, but named only
 * under a key this device does not hold; not found; or no answer.
 *
 * "Not found" is kept apart from every other answer, and only there: it is the one witness a deletion can rest on, and
 * a reply that cannot be read — a blank name, a sealed name the event's own key does not open, a date that is no
 * instant, an event that ends before it starts — is no answer, never a found event with a fact this device invented.
 *
 * An encrypted event's name is sealed (decision record `changes/seal-event-name`): it is opened with the event's key
 * the caller holds — an invite's, or the one kept for the joined event — and, without it, is the name the caller last
 * knew the event by.
 */
class EventDirectory(private val gateway: BackendGateway, private val cipher: MetaCipher) {

    /** [event] as the server holds it, its name opened with [key] or else known as [lastKnown]. */
    suspend fun lookup(event: EventId, key: IdentifiedKey?, lastKnown: EventName?): EventLookupFact = when (
        val reply = gateway.send(
            Routes.GetEvent.request(event.value),
        )
    ) {
        is GetEventReply.Found -> reply.eventDetails.fact(event, Naming(key, lastKnown))
        EventNotFound -> EventLookupFact.NotFound
        // The service is being updated: it says when asking again is worth it.
        is Maintenance -> EventLookupFact.Unanswered(ServiceFault.UPDATING, reply.retryAfter.trim().toLongOrNull())
        is ClientError, is ServerIssue, is NoAnswer ->
            EventLookupFact.Unanswered(ServiceFaults.of(reply) ?: ServiceFault.UNREACHABLE)
    }

    private fun EventDetails.fact(id: EventId, naming: Naming): EventLookupFact {
        val encryption = EncryptionText.parse(keyId)
        val read = cipher.nameRead(name, sealedMeta, encryption, naming.key)
        return when (val name = NameVerdict.of(read, naming.lastKnown)) {
            is NameVerdict.Named -> when (val event = event(id, name.name)) {
                is Parsed.Ok -> EventLookupFact.Found(event.value, completed = completedAt != null)
                // A served event missing what it always carries is an answer the app does not understand.
                is Parsed.Refused -> EventLookupFact.Unanswered(ServiceFault.UNREACHABLE)
            }
            NameVerdict.Withheld -> EventLookupFact.Withheld(encryption, completed = completedAt != null)
            NameVerdict.Unreadable -> EventLookupFact.Unanswered(ServiceFault.UNREACHABLE)
        }
    }

    private fun EventDetails.event(id: EventId, name: EventName): Parsed<Event> =
        range().then { range -> instant(deletesAt).then { deletesAt -> Parsed.Ok(event(id, name, range, deletesAt)) } }

    private fun EventDetails.event(
        id: EventId,
        name: EventName,
        range: EventRange,
        deletesAt: Timestamp,
    ): Event = Event(
        id = id,
        name = name,
        range = range,
        // Not a fact a membership rests on: an unreadable one costs only the "guests can join until" line.
        openUntil = TimestampText.parseOrNull(openUntil)?.let(::OpenUntil),
        deletesAt = DeletesAt(deletesAt),
        encryption = EncryptionText.parse(keyId),
        members = MemberCounts(members.active, members.`final`, members.waiting),
    )

    private fun EventDetails.range(): Parsed<EventRange> =
        instant(startsAt).then { start -> instant(endsAt).then { end -> ordered(EventStart(start), EventEnd(end)) } }

    private fun ordered(start: EventStart, end: EventEnd): Parsed<EventRange> = when (
        val range = EventRange.of(
            start,
            end,
        )
    ) {
        is EventRangeVerdict.Ok -> Parsed.Ok(range.range)
        EventRangeVerdict.Inverted -> Parsed.Refused("it ends before it starts")
    }
}

/** How a served name is read: with [key], else as [lastKnown]. */
private class Naming(val key: IdentifiedKey?, val lastKnown: EventName?)
