package app.snapsync.feature.status

import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus

import app.snapsync.ports.GalleryStatusSource
import app.snapsync.model.grantsPhotoAccess
import app.snapsync.ports.PhotoAccessStatusSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The real [SyncStatusSource]. Own-device completeness **and** in-flight activity are read from the
 * extension's ledger ([LedgerCountsSource] — `completed` and `pending` from one consistent
 * `aggregates()` read); the upload **total** is the live own-device gallery size ([GalleryStatusSource]);
 * `active` is derived from permission. The source issues **no storage LIST** for upload status — this is
 * the notify-driven, ledger-sourced projection (spec: sync-status). Reading the ledger for classification
 * is safe under the **no-deletion-during-an-active-event** invariant (the ledger cannot over-count; the
 * sole ledger↔storage divergence point, (re)join, is reconciled by `event-rejoin-reconciliation`).
 *
 * Like any source backed by an asynchronous first read, the factory does NOT suspend: it seeds
 * [SyncStatus.Loading] and, on [scope], collects the three inputs combined, emitting [SyncStatus.Ready]
 * once **each input has been READ** and on every change after.
 *
 * ⚠️ **A `StateFlow`'s seed is not a read**, and this is the whole reason the gate below exists. Every
 * input here is a `StateFlow`, so all three "have a value" the instant they are constructed and
 * `combine` emits on its first dispatch — before any enumeration, any ledger read, any union read. This
 * source used to mint a snapshot from those seeds, producing `total = 0`, `completed = 0`; the health
 * rule hides a direction arrow when `synced >= total`, `0 >= 0` holds on BOTH arms, and the joined
 * screen therefore rendered a check mark reading "In sync" on a device that had read nothing. Members
 * reported it as the status going backwards when the real counts arrived seconds or minutes later
 * (`SNAPSYNC-14`, `SNAPSYNC-16`). The specs said `Ready` waits for all three inputs the whole time; a
 * seeded value satisfied that vacuously, so the read-ness now lives in the input types themselves —
 * [GalleryStatusSource]'s nullable size and [LedgerCounts.read] — and cannot be satisfied by existing.
 *
 * A **counted** zero is a read value and does mint a snapshot: a non-contributing membership settles
 * the screen exactly as it always has.
 *
 * Each minted [SyncProgress] sets `completed` =
 * the ledger complete-asset count, `total` = the gallery size, `pending` = `min(ledgerPending, total −
 * completed)` — the ledger in-flight count **clamped to remaining** so a deleted-but-not-yet-pruned
 * photo can never read `pending` above the shown remainder (display-only — see [SyncProgress]) —
 * `active = (permission == GRANTED)`, `failed = 0`, and `estimatedRemaining = null`. Its fields stay
 * non-nullable: the un-read state is carried by [SyncStatus.Loading], never as a hole inside a
 * snapshot.
 *
 * Liveness is trigger-driven plus a foreground-gated poll: the [LedgerCountsSource] refreshes on
 * foreground entry, on each [LedgerCountsPoller] tick while foregrounded (migration step 12 — the
 * cross-process ding's replacement), and (app-driven tier) after each pump cycle — each a local
 * ledger read, no network.
 */
fun LedgerBackedSyncStatusSource(
    ledgerCounts: LedgerCountsSource,
    permission: PhotoAccessStatusSource,
    gallery: GalleryStatusSource,
    scope: CoroutineScope,
): SyncStatusSource {
    val status = MutableStateFlow<SyncStatus>(SyncStatus.Loading)
    scope.launch {
        combine(
            ledgerCounts.counts,
            permission.permission,
            gallery.size,
        ) { counts, perm, total ->
            // The read gate. `total == null` is "the library was never enumerated" and `!counts.read`
            // is "the ledger was never read" — both distinct from the zeros they used to be seeded as.
            // Staying Loading here is what keeps the screen from claiming everything is shared before
            // anything has been looked at.
            if (total == null || !counts.read) return@combine SyncStatus.Loading
            val completedCount = counts.completed
            val remaining = (total - completedCount).coerceAtLeast(0)
            SyncStatus.Ready(
                SyncProgress(
                    // Ledger in-flight, clamped to remaining (display-only — see SyncProgress).
                    pending = minOf(counts.pending, remaining),
                    completed = completedCount,
                    total = total,
                    failed = 0,
                    // Usable access: syncing is operational under both a full and a limited grant
                    // (capability `limited-photo-access` — under LIMITED the total is selection-scoped).
                    active = perm.grantsPhotoAccess,
                    estimatedRemaining = null,
                ),
            )
            // Only a Ready is ever published. `status` is already seeded Loading, so writing Loading
            // back would be the one thing the seam forbids — "once Ready, a source MUST NOT regress to
            // Loading" (`sync-status`). The gate above therefore decides when Loading ENDS, and cannot
            // resurrect it if an input were ever to un-read itself.
        }.collect { next -> if (next is SyncStatus.Ready) status.value = next }
    }
    return object : SyncStatusSource {
        override val status: StateFlow<SyncStatus> = status
    }
}
