package app.snapsync.status

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Snapshot of backup truth, projected from the engine's ledger and the live photo library
 * (design.md §2.4).
 *
 * [completed] is a lifetime aggregate over the ledger, counted by **photo (asset), not resource
 * row**: photos all of whose resources are `COMPLETED`. A re-upload flips its photo back to pending
 * — re-uploads are visible here. [pending] (ledger photos not yet complete) remains available but
 * does **not** drive classification.
 *
 * [total] is the live photo-library count (the gallery size, `N`) — NOT a ledger count, so it
 * reflects photos the ledger has not yet discovered. This is what makes "n of N" honest the instant
 * a photo is taken, before the background extension records anything.
 *
 * [failed] is structurally 0 from the ledger-backed source (retry-forever never gives up a key) and
 * [estimatedRemaining] is always null (this version never estimates). Both fields exist for fakes.
 *
 * [active] is operational state — "the backup machinery is allowed to run" — derived from
 * permission. It no longer drives classification (the setup gate shadows every inactive case), but
 * is retained as the shared operational-state rule's output.
 *
 * [lastFinishedAt] is the newest completion recorded in the ledger; `null` means nothing has ever
 * completed.
 */
data class SyncProgress(
    val pending: Int,
    val completed: Int,
    val total: Int,
    val failed: Int,
    val active: Boolean,
    val estimatedRemaining: Duration?,
    val lastFinishedAt: Instant?,
) {
    /**
     * The displayed synced count: [completed] clamped to [total]. A photo uploaded then deleted
     * stays `COMPLETED` in the ledger until the next extension prune while [total] drops instantly,
     * so without the clamp `completed` could exceed `total` and read as a nonsensical "6 of 5".
     */
    val synced: Int get() = minOf(completed, total)

    /**
     * Single source of truth for classifying a snapshot, driven by the live total `N` versus the
     * (clamped) synced count `n` — ledger `pending` is deliberately ignored so a not-yet-pruned
     * deleted photo cannot pin the screen to IN_PROGRESS. There is no SUSPENDED state (the setup
     * gate shadows every inactive case), no NEVER_SYNCED (it folds into IN_PROGRESS at n=0 or
     * NOTHING_TO_SYNC at N=0), and no INCOMPLETE/FAILED (untellable under retry-forever).
     */
    val state: SyncState
        get() = when {
            total == 0 -> SyncState.NOTHING_TO_SYNC
            synced >= total -> SyncState.COMPLETE
            else -> SyncState.IN_PROGRESS
        }
}

enum class SyncState {
    IN_PROGRESS,
    COMPLETE,
    NOTHING_TO_SYNC,
}
