package app.snapsync.eventcreation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The create-event lifecycle, kept as its own tiny seam (the join-status twin) so the presentation
 * reduction folds it in without depending on the create orchestration (which pulls ktor). It has
 * exactly three shapes:
 * - [Idle]: no create in flight (the create input is shown).
 * - [InFlight]: the `POST /event` request is running (the screen shows a preparing state).
 * - [Failed]: the request failed; the input is shown with an inline error matching [reason]. There
 *   is deliberately **no** success value — a successful create provisions config, which moves the
 *   reduction off the create layer entirely.
 */
sealed interface CreationStatus {
    data object Idle : CreationStatus

    data object InFlight : CreationStatus

    data class Failed(val reason: CreationFailureReason) : CreationStatus
}

/** Why a create attempt failed, so the screen shows the right copy. */
enum class CreationFailureReason {
    /** The backend rejected the name (`400`). */
    INVALID_NAME,

    /** A transient/server failure (non-2xx other than 400, transport, or parse). */
    SERVER,
}

/**
 * The command port for creating an event: fire-and-forget, like `PermissionRequester`. It MUST NOT
 * return a value and MUST NOT suspend; the outcome arrives exclusively via [CreationStatusSource]
 * (in-flight then either config becoming present, or [CreationStatus.Failed]).
 */
interface EventCreator {
    fun create(name: String)
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
    override fun create(name: String) = Unit
}
