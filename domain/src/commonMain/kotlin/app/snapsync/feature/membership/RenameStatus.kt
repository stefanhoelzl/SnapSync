package app.snapsync.feature.membership

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The rename-event lifecycle (capability `event-rename`), kept as its own tiny seam like the create and
 * join twins so the presentation reduction folds it in without depending on the rename orchestration
 * (which pulls ktor). It has four shapes:
 * - [Idle]: no rename in flight (the resting state; also where the screen resets it to).
 * - [InFlight]: the `PATCH /events/:id` request is running (the dialog shows a busy state and refuses
 *   both confirm and dismissal).
 * - [Succeeded]: the rename completed **and** the echoed name is persisted (the dialog closes).
 * - [Failed]: the request failed; the dialog stays open with an error banner matching [reason].
 *
 * ⚠️ [Succeeded] is where this DIVERGES from `CreationStatus`, and the divergence is the point. That twin
 * deliberately has **no** success value because "a successful create provisions config, which moves the
 * reduction off the create layer entirely" — the screen learns of success by the layer changing under it.
 * A successful rename changes no layer: the member stays on the joined status screen with a dialog that
 * must close. Inferring that from a return to [Idle] would require the screen to remember it had seen
 * [InFlight], an inference that breaks the moment the sequence changes; so the success is stated.
 *
 * The cost of stating it is that [Succeeded] is a **latch** the screen must clear — hence [ResetRename].
 */
sealed interface RenameStatus {
    data object Idle : RenameStatus

    data object InFlight : RenameStatus

    data object Succeeded : RenameStatus

    data class Failed(val reason: RenameFailureReason) : RenameStatus
}

/**
 * Why a rename attempt failed, so the dialog shows the right copy.
 *
 * There is deliberately **no** reason for a missing event, even though the backend `404`s one: a `404` is
 * a *single* witness that the event is gone, and the self-leave (capability `leave-event`) requires two
 * independent witnesses — one of them offline — precisely so no backend fault can destroy every
 * membership at once. Giving it copy would give it a meaning, and a meaning invites a future change to
 * act on it. It arrives here as [SERVER] like any other non-`400`; see `RenameOutcome.Transient`.
 */
enum class RenameFailureReason {
    /** The backend rejected the name (`400`): empty after trimming, or over 100 characters. */
    INVALID_NAME,

    /** A transient/server failure (non-2xx other than `400` — including `404` — transport, or parse). */
    SERVER,
}

/**
 * The command port for renaming the joined event: fire-and-forget, like `EventCreator`. It MUST NOT
 * return a value and MUST NOT suspend; the outcome arrives exclusively via [RenameStatusSource].
 *
 * [name] is passed as typed; the use-case trims it (the same split `EventCreator`/`CreateEvent` use).
 */
interface EventRenamer {
    suspend fun rename(eventId: String, name: String)
}

/**
 * The command that returns [RenameStatusSource] to [RenameStatus.Idle] — the latch-clearing half of
 * [RenameStatus.Succeeded]. Fired by the screen after it consumes a terminal status, so a second rename
 * starts from a clean sequence rather than re-reading the previous one's outcome.
 */
interface ResetRename {
    fun reset()
}

/** Read face of the rename status — what the presentation layer consumes. */
interface RenameStatusSource {
    val renameStatus: StateFlow<RenameStatus>
}

/** Settable [RenameStatusSource] the rename use-case drives and the harness/tests forge. */
class MutableRenameStatusSource(initial: RenameStatus = RenameStatus.Idle) : RenameStatusSource {
    private val _status = MutableStateFlow(initial)
    override val renameStatus: StateFlow<RenameStatus> = _status.asStateFlow()

    fun set(value: RenameStatus) {
        _status.value = value
    }
}

/** A no-op [EventRenamer] for hosts/tests that forge [RenameStatus] directly (e.g. the harness). */
object NoOpEventRenamer : EventRenamer {
    override suspend fun rename(eventId: String, name: String) = Unit
}

/** A no-op [ResetRename], the twin of [NoOpEventRenamer]. */
object NoOpResetRename : ResetRename {
    override fun reset() = Unit
}
