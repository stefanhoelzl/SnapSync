package app.snapsync.services.backend

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.faultOf
import app.snapsync.domain.event.EventId
import app.snapsync.domain.service.ServiceFault
import app.snapsync.model.CreateOutcome
import app.snapsync.wire.api.CreateEventReply
import app.snapsync.wire.api.Invalid
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.Unauthorized
import app.snapsync.wire.models.EventCreate

/**
 * Minting an event. [endsAt] is the host's chosen event-window end (a canonical `…Z` string); it is **nullable**
 * because the backend treats an absent `endsAt` as "use the legacy `startsAt + 30d`".
 * The interactive create always supplies one (the create screen requires a range).
 */
interface EventCreation {
    /**
     * [keyId] names an ENCRYPTED event's key (the key never leaves the device); `null` creates a plain event.
     * [openUntil] is the day guests may join until when the event is created after its end (a canonical `…Z` string);
     * `null` is the event's end, as the backend reads an absent one.
     */
    suspend fun create(
        name: String,
        startsAt: String,
        endsAt: String?,
        keyId: String? = null,
        openUntil: String? = null,
    ): CreateOutcome
}

/**
 * [EventCreation] over the backend: a created event answers [CreateOutcome.Created] with the minted id and the name
 * the backend stored; a refused date range is [CreateOutcome.InvalidWindow] and any other refused request
 * ([CreateEventReply.InvalidName], [Invalid]) is [CreateOutcome.InvalidName]; a refused credential is
 * [CreateOutcome.Unverified]; every other answer is [CreateOutcome.Transient]. Which field was refused is the
 * description's to say (`api/openapi.yaml`, `createEvent`'s `400`), so it is read off the reply, never the body.
 *
 * `startsAt` is sent **verbatim**: the caller's contract is that it is already the canonical cutoff shape,
 * and the backend rejects anything else with a `400`. Reformatting or re-deriving it
 * here would introduce a second origin for a value whose whole point is having exactly one.
 *
 * [zone] answers the device's zone at the moment of the create — the zone the host picked the range in — which the
 * backend keeps for the event page alone. It never refuses a create.
 */
class BackendEventCreation(
    private val gateway: BackendGateway,
    private val zone: () -> String?,
) : EventCreation {

    override suspend fun create(
        name: String,
        startsAt: String,
        endsAt: String?,
        keyId: String?,
        openUntil: String?,
    ): CreateOutcome {
        val request = Routes.CreateEvent.request(
            EventCreate(
                name = name,
                startsAt = startsAt,
                endsAt = endsAt,
                openUntil = openUntil,
                zone = zone(),
                keyId = keyId,
            ),
        )
        return when (val reply = gateway.sendObtainingFirst(request)) {
            // An id that is no event id is a malformed reply, answered as any other the backend could not have meant.
            is CreateEventReply.Created -> EventId.parse(reply.event.eventId)
                ?.let { CreateOutcome.Created(eventId = it, name = reply.event.name) }
                ?: CreateOutcome.Transient(ServiceFault.UNREACHABLE)
            CreateEventReply.InvalidWindow -> CreateOutcome.InvalidWindow
            CreateEventReply.InvalidName, is Invalid -> CreateOutcome.InvalidName
            Unauthorized -> CreateOutcome.Unverified
            else -> CreateOutcome.Transient(faultOf(reply) ?: ServiceFault.UNREACHABLE)
        }
    }
}
