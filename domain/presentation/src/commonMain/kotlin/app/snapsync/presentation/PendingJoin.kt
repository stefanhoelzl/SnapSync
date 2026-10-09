package app.snapsync.presentation

import app.snapsync.model.JoinPhase

/**
 * An in-progress interactive join/switch confirmation: the event being
 * joined and the [phase] of its confirmation surface. The reducer maps a non-null value to
 * `Layer.JoiningEvent` (config absent) or `Joined.pendingSwitch` (config present); `null` means no
 * confirmation is open.
 *
 * Held in a `MutableStateFlow` ([StatusSources.pending]) that the container both reads and writes: its gate
 * methods advance the [phase], and the reduction reads it. A test may inject one to start the gate at any
 * `JoinPhase`.
 */
data class PendingJoin(
    val eventId: String,
    val phase: JoinPhase,
    /** The key the invite link carried, `null` when it carried none — committed with the join, shown nowhere. */
    val linkKey: String? = null,
    /** The loaded event's key id, once its details loaded — what [linkKey] was checked against. */
    val eventKeyId: String? = null,
) {
    /** Never the key: a pending join reaches logs and test failures. */
    override fun toString(): String = "PendingJoin(eventId=$eventId, phase=$phase, linkKey=${linkKey?.let { "present" }})"
}
