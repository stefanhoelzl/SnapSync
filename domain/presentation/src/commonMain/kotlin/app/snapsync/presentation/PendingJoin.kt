package app.snapsync.presentation

import app.snapsync.model.JoinPhase

/**
 * An in-progress interactive join/switch confirmation (capability `join-event`): the event being
 * joined and the [phase] of its confirmation surface. The reducer maps a non-null value to
 * `Layer.JoiningEvent` (config absent) or `Joined.pendingSwitch` (config present); `null` means no
 * confirmation is open.
 *
 * Held in a `MutableStateFlow` ([StatusSources.pending]) that the container both reads and writes: its gate
 * methods advance the [phase], and the reduction reads it. A test may inject one to start the gate at any
 * `JoinPhase`.
 */
data class PendingJoin(val eventId: String, val phase: JoinPhase)
