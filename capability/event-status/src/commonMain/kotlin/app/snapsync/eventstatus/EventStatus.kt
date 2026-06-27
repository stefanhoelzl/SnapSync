package app.snapsync.eventstatus

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The join lifecycle for the current event (re-join reconciliation), kept as its own tiny seam so
 * the presentation layer can read it without depending on the join orchestration (which pulls
 * gallery/engine/ktor). Mirrors the four states the status reduction folds in:
 * - [Idle]: no join in flight or needed (the ledger already reflects the event, or no event).
 * - [Joining]: the list fetch + seed is running (the screen shows a preparing state).
 * - [JoinFailed]: the list fetch failed; the user re-scans the QR to retry (no auto-retry).
 * - [Joined]: the seed succeeded; the sync hero takes over.
 */
enum class EventStatus { Idle, Joining, JoinFailed, Joined }

/** Read face of the join status — what the presentation reduction consumes. */
interface EventStatusSource {
    val status: StateFlow<EventStatus>
}

/** Settable [EventStatusSource] the join use-case drives and the harness/tests forge. */
class MutableEventStatusSource(initial: EventStatus = EventStatus.Idle) : EventStatusSource {
    private val _status = MutableStateFlow(initial)
    override val status: StateFlow<EventStatus> = _status.asStateFlow()

    fun set(value: EventStatus) {
        _status.value = value
    }
}
