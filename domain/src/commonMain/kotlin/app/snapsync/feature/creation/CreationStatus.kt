package app.snapsync.feature.creation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The create-event lifecycle, kept as its own tiny seam (the join-status twin) so the presentation
 * reduction folds it in without depending on the create orchestration (which pulls ktor). It has
 * exactly three shapes:
 * - [Idle]: no create in flight (the create input is shown).
 * - [InFlight]: the `POST /events` request is running (the screen shows a preparing state).
 * - [Failed]: the request failed; the input is shown with an inline error matching [reason]. There
 *   is deliberately **no** success value — a successful create provisions config, which moves the
 *   reduction off the create layer entirely.
 */
sealed interface CreationStatus {
    data object Idle : CreationStatus

    data object InFlight : CreationStatus

    data class Failed(val reason: CreationFailureReason) : CreationStatus
}

/**
 * Why a create attempt failed, so the screen shows the right copy.
 *
 * There is deliberately **no** reason for an invalid `startsAt`, even though the backend 400s one: the
 * app always sends a canonical value (it comes from a picker, converted through the one cutoff codec),
 * so a startsAt-shaped 400 is unreachable from this client. Inventing user-facing copy for a state no
 * user can reach would be dead surface — the single 400 → [INVALID_NAME] mapping stands.
 */
enum class CreationFailureReason {
    /** The backend rejected the request (`400`). In practice: the name. */
    INVALID_NAME,

    /** A transient/server failure (non-2xx other than 400, transport, or parse). */
    SERVER,
}

/**
 * The command port for creating an event: fire-and-forget, like `PhotoAccessRequester`. It MUST NOT
 * return a value and MUST NOT suspend; the outcome arrives exclusively via [CreationStatusSource]
 * (in-flight then either config becoming present, or [CreationStatus.Failed]).
 *
 * [startsAt] is the event's start date — the host's statement of when the event began (capability
 * `event-creation`). It arrives here **already canonical** (`yyyy-MM-dd'T'HH:mm:ss'Z'`, capability
 * `photo-selection-policy`), converted from the user's local pick by the caller, so this capability needs no
 * clock, no timezone, and no dependency on the cutoff codec.
 */
interface EventCreator {
    fun create(name: String, startsAt: String, endsAt: String)
}

/** Read face of the create status — what the presentation reduction consumes. */
interface CreationStatusSource {
    val creationStatus: StateFlow<CreationStatus>
}

/** Settable [CreationStatusSource] the create use-case drives and the harness/tests forge. */
class MutableCreationStatusSource(initial: CreationStatus = CreationStatus.Idle) : CreationStatusSource {
    private val _status = MutableStateFlow(initial)
    override val creationStatus: StateFlow<CreationStatus> = _status.asStateFlow()

    fun set(value: CreationStatus) {
        _status.value = value
    }
}

/** A no-op [EventCreator] for hosts/tests that forge [CreationStatus] directly (e.g. the harness). */
object NoOpEventCreator : EventCreator {
    override fun create(name: String, startsAt: String, endsAt: String) = Unit
}
