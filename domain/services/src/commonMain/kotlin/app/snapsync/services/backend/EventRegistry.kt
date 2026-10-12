package app.snapsync.services.backend

import app.snapsync.model.CreateOutcome
import app.snapsync.model.RenameOutcome
import app.snapsync.wire.api.ChangeEventReply
import app.snapsync.wire.api.CreateEventReply
import app.snapsync.wire.api.Invalid
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.Unauthorized
import app.snapsync.wire.models.EventChange
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
 * Renaming an event. Non-throwing: a transport or parse error maps to
 * [RenameOutcome.Transient], never an exception.
 *
 * [name] arrives **already trimmed** by the caller, matching [EventCreation]'s contract; the backend trims again and
 * its echo is authoritative.
 */
fun interface EventRename {
    suspend fun rename(eventId: String, name: String): RenameOutcome
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
        return when (val reply = gateway.send(request, obtainFirst = true)) {
            is CreateEventReply.Created -> CreateOutcome.Created(eventId = reply.event.eventId, name = reply.event.name)
            CreateEventReply.InvalidWindow -> CreateOutcome.InvalidWindow
            CreateEventReply.InvalidName, is Invalid -> CreateOutcome.InvalidName
            Unauthorized -> CreateOutcome.Unverified
            else -> CreateOutcome.Transient
        }
    }
}

/**
 * [EventRename] over the backend's event change: a changed event answers the name the backend stored; a refused
 * request ([Invalid]) is [RenameOutcome.InvalidName]; every other answer is [RenameOutcome.Transient].
 *
 * ⚠️ A `404` maps to [RenameOutcome.Transient] like every other answer but a refused request, and that collapse is deliberate
 * rather than incidental — see [RenameOutcome.Transient] for why a lone `404` must never acquire a distinct meaning
 * on this path.
 *
 * The stored name is what the caller persists. It is **required** (`Event.name`): an answer that carries none does
 * not decode, and is [RenameOutcome.Transient] rather than a success reporting a name this client invented.
 */
class BackendEventRename(private val gateway: BackendGateway) : EventRename {

    override suspend fun rename(eventId: String, name: String): RenameOutcome =
        when (val reply = gateway.send(Routes.ChangeEvent.request(eventId, EventChange(name = name)))) {
            is ChangeEventReply.Changed -> RenameOutcome.Renamed(reply.event.name)
            is Invalid -> RenameOutcome.InvalidName
            else -> RenameOutcome.Transient
        }
}
