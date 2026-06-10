package app.snapsync.presentation

/**
 * Display-ready projection of a sync snapshot: pre-formatted strings and the rough progress
 * fraction. The UI renders these verbatim — all formatting and time arithmetic happens in
 * presentation so tests assert exact visible text.
 */
sealed interface UiState {
    data object NeverSynced : UiState

    data class InProgress(val fraction: Float, val estimate: String) : UiState

    data object Suspended : UiState

    data class Complete(val finishedAgo: String) : UiState

    data class Incomplete(val finishedAgo: String) : UiState

    data class Failed(val finishedAgo: String) : UiState
}
