package app.snapsync.data.event

import app.snapsync.data.Parsed
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.faultOf
import app.snapsync.data.eventName
import app.snapsync.data.instant
import app.snapsync.data.then
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.Event
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventKeyId
import app.snapsync.domain.event.EventLookupFact
import app.snapsync.domain.event.EventName
import app.snapsync.domain.event.EventRange
import app.snapsync.domain.event.EventRangeVerdict
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.MemberCounts
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.time.Timestamp
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
 * The server's word about an event (`getEvent`): found, with every fact a membership keeps; not found; or no answer.
 *
 * "Not found" is kept apart from every other answer, and only there: it is the one witness a deletion can rest on, and
 * a reply that cannot be read — a blank name, a date that is no instant, an event that ends before it starts — is no
 * answer, never a found event with a fact this device invented.
 */
class EventDirectory(private val gateway: BackendGateway) {

    /** [event] as the server holds it. */
    suspend fun lookup(event: EventId): EventLookupFact = when (
        val reply = gateway.send(
            Routes.GetEvent.request(event.value),
        )
    ) {
        is GetEventReply.Found -> reply.eventDetails.fact(event)
        EventNotFound -> EventLookupFact.NotFound
        // The service is being updated: it says when asking again is worth it.
        is Maintenance -> EventLookupFact.Unanswered(ServiceFault.UPDATING, reply.retryAfter.trim().toLongOrNull())
        is ClientError, is ServerIssue, is NoAnswer ->
            EventLookupFact.Unanswered(faultOf(reply) ?: ServiceFault.UNREACHABLE)
    }
}

private fun EventDetails.fact(id: EventId): EventLookupFact = when (val event = event(id)) {
    is Parsed.Ok -> EventLookupFact.Found(event.value, completed = completedAt != null)
    // A served event missing what it always carries is an answer the app does not understand.
    is Parsed.Refused -> EventLookupFact.Unanswered(ServiceFault.UNREACHABLE)
}

private fun EventDetails.event(id: EventId): Parsed<Event> = eventName(name).then { name ->
    range().then { range -> instant(deletesAt).then { deletesAt -> Parsed.Ok(event(id, name, range, deletesAt)) } }
}

private fun EventDetails.event(id: EventId, name: EventName, range: EventRange, deletesAt: Timestamp): Event = Event(
    id = id,
    name = name,
    range = range,
    // Not a fact a membership rests on: an unreadable one costs only the "guests can join until" line.
    openUntil = TimestampText.parseOrNull(openUntil)?.let(::OpenUntil),
    deletesAt = DeletesAt(deletesAt),
    keyId = keyId?.let(::EventKeyId),
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
