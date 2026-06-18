package app.snapsync.presentation

import app.snapsync.permission.PermissionStatus

/**
 * Display-ready projection of a sync snapshot: pre-formatted strings and the rough progress
 * fraction. The UI renders these verbatim — all formatting and time arithmetic happens in
 * presentation so tests assert exact visible text.
 */
sealed interface UiState {
    /**
     * Permission granted, but persisted ledger state has not been read yet — the honest first
     * frame over a real backend. Reachable only when config is present and permission is GRANTED;
     * auto-resolves to a sync state.
     */
    data object Loading : UiState

    /**
     * The setup gate (setup-gate capability): shown until storage is connected **and** photo
     * permission is GRANTED. Carries enough to render the two checkable cards — whether the
     * storage step is satisfied, and the current [permission] status driving the permission card's
     * copy and CTA.
     */
    data class Setup(val storageConnected: Boolean, val permission: PermissionStatus) : UiState

    data object NeverSynced : UiState

    data class InProgress(val fraction: Float, val estimate: String) : UiState

    data object Suspended : UiState

    data class Complete(val finishedAgo: String) : UiState

    data class Incomplete(val finishedAgo: String) : UiState
}
