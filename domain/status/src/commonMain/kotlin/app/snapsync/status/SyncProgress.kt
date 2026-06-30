package app.snapsync.status

import kotlin.time.Duration

/**
 * Snapshot of backup truth (design.md §2.4): completeness and classification come from **own-device
 * storage truth** (each asset's expected resources present in the per-device file listing) and the
 * live photo library; [pending] alone comes from a **read-only peek at the ledger's in-flight count**.
 *
 * [completed] is the count of **complete assets** — an asset all of whose expected resources are
 * present in the per-device file listing — counted by **photo (asset), not resource row**.
 * [pending] is the ledger-reported **in-flight** asset count (photos with a job created but not yet
 * `COMPLETED`), **clamped to remaining** (`min(inFlight, total − completed)`); it is **display-only**
 * — it does **not** drive classification.
 *
 * [total] is the live photo-library count (the gallery size, `N`) — NOT a storage count, so it
 * reflects photos not yet uploaded. This is what makes "n of N" honest the instant a photo is taken,
 * before the background extension uploads anything.
 *
 * [failed] is structurally 0 from the listing-backed source (retry-forever never gives up a key) and
 * [estimatedRemaining] is always null (this version never estimates). Both fields exist for fakes.
 *
 * [active] is operational state — "the backup machinery is allowed to run" — derived from
 * permission. It no longer drives classification (the setup gate shadows every inactive case), but
 * is retained as the shared operational-state rule's output.
 *
 * There is no completion timestamp: the status surface reports completeness and live activity only,
 * never how long ago anything happened.
 */
data class SyncProgress(
    val pending: Int,
    val completed: Int,
    val total: Int,
    val failed: Int,
    val active: Boolean,
    val estimatedRemaining: Duration?,
) {
    /**
     * The displayed synced count: [completed] clamped to [total]. A photo uploaded then deleted
     * stays complete in storage until pruned while [total] drops instantly, so without the clamp
     * `completed` could exceed `total` and read as a nonsensical "6 of 5".
     */
    val synced: Int get() = minOf(completed, total)

    /**
     * Single source of truth for classifying a snapshot, driven by the live total `N` versus the
     * (clamped) synced count `n` — `pending` is deliberately ignored so a not-yet-pruned
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
