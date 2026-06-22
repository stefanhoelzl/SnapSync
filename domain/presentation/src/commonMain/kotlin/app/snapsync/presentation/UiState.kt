package app.snapsync.presentation

import app.snapsync.permission.PermissionStatus

/**
 * Display-ready projection of a sync snapshot: pre-formatted strings and the counts the screen
 * shows verbatim. All formatting and time arithmetic happens in presentation so tests assert exact
 * visible text.
 */
sealed interface UiState {
    /**
     * Permission granted and config present, but persisted state has not been read yet — the honest
     * first frame over a real backend (the source is `Loading` until the ledger AND the gallery size
     * have both produced a first value). Auto-resolves to a sync state.
     */
    data object Loading : UiState

    /**
     * The setup gate (setup-gate capability): shown until storage is connected **and** photo
     * permission is GRANTED. Carries enough to render the two checkable cards — whether the storage
     * step is satisfied, and the current [permission] status driving the permission card's copy and
     * CTA.
     */
    data class Setup(val storageConnected: Boolean, val permission: PermissionStatus) : UiState

    /**
     * Sync underway: [synced] of [total] photos uploaded (`synced` is already clamped to `total`).
     * [finishedAgo] is the relative time of the most recent completion, or `null` when nothing has
     * completed yet (a virgin "0 of N").
     */
    data class InProgress(val synced: Int, val total: Int, val finishedAgo: String?) : UiState

    /** Nothing to upload yet — the library holds no in-scope photos (`total == 0`). */
    data object NothingToSync : UiState

    /** Every present photo uploaded: [total] synced, finished [finishedAgo]. */
    data class Completed(val total: Int, val finishedAgo: String) : UiState
}
