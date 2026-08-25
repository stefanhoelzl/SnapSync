package app.snapsync.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * An in-progress interactive join/switch confirmation (capability `join-event`): the event being
 * joined and the [phase] of its confirmation surface. The reducer maps a non-null value to
 * `UiState.JoiningEvent` (config absent) or `Joined.pendingSwitch` (config present); `null` means no
 * confirmation is open.
 */
data class PendingJoin(val eventId: String, val phase: JoinPhase)

/**
 * The join/switch overlay cell (capability `join-event`) — an injectable seam over the container's
 * in-progress-join state, mirroring the container's other injected read-models.
 *
 * Unlike those, the container both **reads and writes** this: its gate methods
 * (`onOpenUrl → startPending → loadInto`, `onConfirmJoin`, `onCancelJoin`, …) advance the [phase], and
 * the `combine`/first-frame reduction reads it. So the seam is a single concrete mutable holder rather
 * than an interface + impl pair: production and the full-stack harness accept the default instance
 * (the gate drives it as before), while the forge harness injects its own and calls [set] to forge any
 * `JoinPhase` directly — forging the *input* cell, never fabricating a `UiState`.
 */
class MutablePendingJoinSource(initial: PendingJoin? = null) {
    private val cell = MutableStateFlow(initial)
    val state: StateFlow<PendingJoin?> = cell

    fun set(value: PendingJoin?) {
        cell.value = value
    }
}
