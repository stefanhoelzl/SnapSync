package app.snapsync.data.event

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ServiceFaults
import app.snapsync.domain.event.CreateResult
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.IdentifiedKey
import app.snapsync.domain.request.CreateRequest
import app.snapsync.domain.service.ServiceFault
import app.snapsync.format.TimestampText
import app.snapsync.wire.api.ClientError
import app.snapsync.wire.api.CreateEventReply
import app.snapsync.wire.api.Invalid
import app.snapsync.wire.api.NoAnswer
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.ServerIssue
import app.snapsync.wire.api.Unauthorized
import app.snapsync.wire.models.EventCreate

/**
 * **Minting an event** (`createEvent`), translating only: the request in the wire's form, its name trimmed as the
 * server keeps it, and its answer as what the create came to. A created event answers its id, with [key] — an encrypted
 * event's, of which only the id is sent — for the creator's join; a refused date range is [CreateResult.InvalidWindow]
 * and any other refused request is [CreateResult.InvalidName]; a refused credential is [CreateResult.Unverified]; every
 * other answer is no answer. Which field was refused is the description's to say (`api/openapi.yaml`, `createEvent`'s
 * `400`), read off the reply. The zone the host picked the range in is sent for the event page alone.
 */
class EventCreation(private val gateway: BackendGateway) {

    /** Mint [request]'s event — encrypted under [key] when one is given. */
    suspend fun create(request: CreateRequest, key: IdentifiedKey?): CreateResult {
        val sent = Routes.CreateEvent.request(
            EventCreate(
                name = request.name.trim(),
                startsAt = TimestampText.format(request.startsAt.at),
                endsAt = TimestampText.format(request.endsAt.at),
                openUntil = request.openUntil?.at?.let(TimestampText::format),
                zone = request.zone,
                keyId = key?.id?.value,
            ),
        )
        return resultOf(gateway.sendObtainingFirst(sent), key)
    }

    /** An id that is no event id is a malformed reply, answered as any other the backend could not have meant. */
    private fun created(reply: CreateEventReply.Created, key: IdentifiedKey?): CreateResult =
        EventId.parse(reply.event.eventId)?.let { CreateResult.Created(it, key?.key) }
            ?: CreateResult.Failed(ServiceFault.UNREACHABLE)

    private fun resultOf(reply: CreateEventReply, key: IdentifiedKey?): CreateResult = when (reply) {
        is CreateEventReply.Created -> created(reply, key)
        CreateEventReply.InvalidWindow -> CreateResult.InvalidWindow
        CreateEventReply.InvalidName, is Invalid -> CreateResult.InvalidName
        Unauthorized -> CreateResult.Unverified
        is ClientError, is ServerIssue, is NoAnswer -> CreateResult.Failed(
            ServiceFaults.of(reply) ?: ServiceFault.UNREACHABLE,
        )
    }
}
