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
     * Permission is not `GRANTED` while an event is connected (config present): the status screen
     * hosts the permission affordance instead of the sync hero. [permission] is one of
     * `NOT_DETERMINED` (never asked — the QR was scanned first; shows the "Allow access" priming) or
     * `DENIED` (revoked or refused; shows the "Open Settings" path). It carries **no** counts — the
     * live gallery total is unavailable without photo access. (Setup-gate precedence: this outranks
     * the sync hero but is itself outranked by an absent config, which shows the gate.)
     */
    data class PermissionBlocked(val permission: PermissionStatus) : UiState

    /**
     * Sync underway: [synced] of [total] photos uploaded (`synced` is already clamped to `total`).
     * [inProgress] is how many photos the ledger is actively uploading right now (the asset-counted
     * `pending` — photos with any not-yet-`COMPLETED` resource); it can be lower than `total - synced`
     * when some photos are not yet discovered by the extension. The screen reports no completion time.
     */
    data class InProgress(
        val synced: Int,
        val total: Int,
        val inProgress: Int,
    ) : UiState

    /** Nothing to upload yet — the library holds no in-scope photos (`total == 0`). */
    data object NothingToSync : UiState

    /** Every present photo uploaded: [total] synced. */
    data class Completed(val total: Int) : UiState
}
