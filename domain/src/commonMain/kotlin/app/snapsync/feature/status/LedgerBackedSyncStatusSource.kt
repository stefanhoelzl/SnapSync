package app.snapsync.feature.status

import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus

import app.snapsync.ports.GalleryStatusSource
import app.snapsync.model.PermissionStatus
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
 * on the first combined value and on every change after. Each minted [SyncProgress] sets `completed` =
 * the ledger complete-asset count, `total` = the gallery size, `pending` = `min(ledgerPending, total −
 * completed)` — the ledger in-flight count **clamped to remaining** so a deleted-but-not-yet-pruned
 * photo can never read `pending` above the shown remainder (display-only — see [SyncProgress]) —
 * `active = (permission == GRANTED)`, `failed = 0`, and `estimatedRemaining = null`.
 *
 * Liveness is event-driven: the composition root refreshes the [LedgerCountsSource] on foreground
 * entry, on the extension's cross-process liveness notification, and (app-driven tier) after each pump
 * cycle — each a local ledger read, no network.
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
            val completedCount = counts.completed
            val remaining = (total - completedCount).coerceAtLeast(0)
            SyncProgress(
                // Ledger in-flight, clamped to remaining (display-only — see SyncProgress).
                pending = minOf(counts.pending, remaining),
                completed = completedCount,
                total = total,
                failed = 0,
                active = perm == PermissionStatus.GRANTED,
                estimatedRemaining = null,
            )
        }.collect { status.value = SyncStatus.Ready(it) }
    }
    return object : SyncStatusSource {
        override val status: StateFlow<SyncStatus> = status
    }
}
