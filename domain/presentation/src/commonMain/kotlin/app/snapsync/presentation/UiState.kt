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
     * The create-event landing layer (event-creation-ui), shown while no event is connected
     * (`config == null`) and no create is in flight. Carries an optional pre-formatted inline
     * [error] — the last create failure's copy (sticky until the next attempt) or a transient
     * invalid-deeplink message — rendered beneath the name input. Config-absent outranks permission,
     * join, and snapshot, so this is the top reduction rung.
     */
    data class CreateEvent(val error: String? = null) : UiState

    /**
     * A `POST /event` create request is in flight (`config == null`, creation status `InFlight`): a
     * preparing spinner with no input. Auto-resolves — success provisions config (off this layer),
     * failure returns to [CreateEvent] with an inline error.
     */
    data object CreatingEvent : UiState

    /**
     * Re-join reconciliation in flight (event-rejoin-reconciliation capability): the app is fetching
     * the event's already-stored files and seeding the ledger before enabling uploads. Shown once
     * config and permission are satisfied, ahead of any sync hero; auto-resolves to a sync state on
     * success. No action — a preparing spinner.
     */
    data object Joining : UiState

    /**
     * The re-join list fetch failed: uploads are not enabled. There is no auto-retry — the user
     * re-scans the event QR to try again. A terminal-until-rescan message, no spinner, no button.
     */
    data object JoinFailed : UiState

    /**
     * Permission is not `GRANTED` while an event is connected (config present): the status screen
     * hosts the permission affordance instead of the sync hero. [permission] is one of
     * `NOT_DETERMINED` (never asked — the QR was scanned first; shows the "Allow access" priming) or
     * `DENIED` (revoked or refused; shows the "Open Settings" path). It carries **no** counts — the
     * live gallery total is unavailable without photo access. (Setup-gate precedence: this outranks
     * the join/sync chain but is itself outranked by an absent config, which shows the gate.)
     */
    data class PermissionBlocked(val permission: PermissionStatus) : UiState

    /**
     * Sync underway: [synced] of [total] photos uploaded (`synced` is already clamped to `total`).
     * [inProgress] is how many photos the ledger is actively uploading right now (the asset-counted
     * `pending` — photos with any not-yet-`COMPLETED` resource); it can be lower than `total - synced`
     * when some photos are not yet discovered by the extension. [finishedAgo] is the relative time of
     * the most recent completion, or `null` when nothing has completed yet (a virgin "0 of N").
     */
    data class InProgress(
        val synced: Int,
        val total: Int,
        val inProgress: Int,
        val finishedAgo: String?,
    ) : UiState

    /** Nothing to upload yet — the library holds no in-scope photos (`total == 0`). */
    data object NothingToSync : UiState

    /** Every present photo uploaded: [total] synced, finished [finishedAgo]. */
    data class Completed(val total: Int, val finishedAgo: String) : UiState
}
