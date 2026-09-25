package app.snapsync.feature.creation.readmodel

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
 * A refused date range has its own reason ([INVALID_WINDOW]) although the create screen cannot produce
 * one: its picker is bounded by the same deployment value the backend validates against, but only as that
 * value stood when THIS build was made. A later, shorter limit reaches an older build as a `400` on the
 * range, and reporting it as a refused name would send the host renaming an event whose name was fine.
 */
enum class CreationFailureReason {
    /** The backend rejected the name (`400`). */
    INVALID_NAME,

    /** The backend rejected the date range (`400` on `startsAt`/`endsAt`) — e.g. longer than it allows. */
    INVALID_WINDOW,

    /** A transient/server failure (non-2xx other than 400, transport, or parse). */
    SERVER,
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
