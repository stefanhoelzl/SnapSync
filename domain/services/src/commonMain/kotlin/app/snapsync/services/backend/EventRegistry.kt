package app.snapsync.services.backend

import app.snapsync.model.CreateEventRequest
import app.snapsync.model.CreateOutcome
import app.snapsync.model.RenameOutcome
import app.snapsync.model.Reply

/**
 * Minting an event. [endsAt] is the host's chosen event-window end (a canonical `…Z` string); it is **nullable**
 * because the backend treats an absent `endsAt` as "use the legacy `startsAt + 30d`".
 * The interactive create always supplies one (the create screen requires a range).
 */
interface EventCreation {
    /** [keyId] names an ENCRYPTED event's key (the key never leaves the device); `null` creates a plain event. */
    suspend fun create(name: String, startsAt: String, endsAt: String?, keyId: String? = null): CreateOutcome
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
 * [EventCreation] over the backend: a served create answers [CreateOutcome.Created] with the minted id and the name
 * the backend stored; a `400` refusing the date range (its body names `startsAt` or `endsAt`) is
 * [CreateOutcome.InvalidWindow] and any other `400` is [CreateOutcome.InvalidName]; every other answer is
 * [CreateOutcome.Transient].
 *
 * The body is the only place the backend says WHICH field it refused, so it is read — but only to pick between two
 * refusals: a `400` whose body names neither date stays a refused name, as every `400` was before the range could
 * be refused on its own.
 *
 * `startsAt` is sent **verbatim**: the caller's contract is that it is already the canonical cutoff shape,
 * and the backend rejects anything else with a `400`. Reformatting or re-deriving it
 * here would introduce a second origin for a value whose whole point is having exactly one.
 *
 * [zone] answers the device's zone at the moment of the create — the zone the host picked the range in — which the
 * backend keeps for the event page alone. It never refuses a create.
 */
class BackendEventCreation(
    private val backend: AuthenticatedBackend,
    private val zone: () -> String?,
) : EventCreation {

    override suspend fun create(name: String, startsAt: String, endsAt: String?, keyId: String?): CreateOutcome =
        when (val reply = backend.createEvent(CreateEventRequest(name, startsAt, endsAt, zone(), keyId))) {
            is Reply.Ok -> CreateOutcome.Created(eventId = reply.value.eventId, name = reply.value.name)
            is Reply.Refused -> when (reply.status) {
                HttpStatus.BAD_REQUEST -> refusal(reply.body)
                HttpStatus.UNAUTHORIZED -> CreateOutcome.Unverified
                else -> CreateOutcome.Transient
            }
            is Reply.Malformed, is Reply.Unreachable -> CreateOutcome.Transient
        }

    private fun refusal(body: String): CreateOutcome =
        if (WINDOW_FIELDS.any { it in body }) CreateOutcome.InvalidWindow else CreateOutcome.InvalidName
}

/** The fields a date-range refusal names (`invalid startsAt` / `invalid endsAt`). */
private val WINDOW_FIELDS = listOf("startsAt", "endsAt")

/**
 * [EventRename] over the backend: a served rename answers the name the backend's echo carries; a `400` is
 * [RenameOutcome.InvalidName]; every other answer is [RenameOutcome.Transient].
 *
 * ⚠️ A `404` maps to [RenameOutcome.Transient] like every other non-`400` status, and that collapse is deliberate
 * rather than incidental — see [RenameOutcome.Transient] for why a lone `404` must never acquire a distinct meaning
 * on this path.
 *
 * The echoed name is what the caller persists. It is **required**: a served rename whose echo carries no name is a
 * malformed answer mapped to [RenameOutcome.Transient] rather than a success reporting a name this client invented.
 */
class BackendEventRename(private val backend: AuthenticatedBackend) : EventRename {

    override suspend fun rename(eventId: String, name: String): RenameOutcome =
        when (val reply = backend.renameEvent(eventId, name)) {
            is Reply.Ok -> reply.value.name?.let { RenameOutcome.Renamed(it) } ?: RenameOutcome.Transient
            is Reply.Refused -> if (reply.status == HttpStatus.BAD_REQUEST) RenameOutcome.InvalidName else RenameOutcome.Transient
            is Reply.Malformed, is Reply.Unreachable -> RenameOutcome.Transient
        }
}
