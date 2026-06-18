package app.snapsync.presentation

/**
 * Display-ready projection of a sync snapshot: pre-formatted strings and the rough progress
 * fraction. The UI renders these verbatim — all formatting and time arithmetic happens in
 * presentation so tests assert exact visible text.
 */
sealed interface UiState {
    /**
     * Permission granted, but persisted ledger state has not been read yet — the honest first
     * frame over a real backend. Reachable only under GRANTED; auto-resolves to a sync state.
     */
    data object Loading : UiState

    /** Permission not yet asked: the gate invites the first system request. */
    data object PermissionAsk : UiState

    /** Permission denied (or partial/restricted): the gate points at system settings. */
    data object PermissionDenied : UiState

    data object NeverSynced : UiState

    data class InProgress(val fraction: Float, val estimate: String) : UiState

    data object Suspended : UiState

    data class Complete(val finishedAgo: String) : UiState

    data class Incomplete(val finishedAgo: String) : UiState
}
