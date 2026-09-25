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
import app.snapsync.feature.status.readmodel.SyncStatusSource

/**
 * The real [SyncStatusSource]. Own-device completeness **and** in-flight activity are read from the
 * ledger's per-photo done-ness ([LedgerCountsSource] — one consistent `assetProgress()` read) and counted
 * over the **admitted set** the gallery counted for the upload total ([GalleryStatusSource]); `active` is
 * derived from permission. The source issues **no storage LIST** for upload status — this is the
 * notify-driven, ledger-sourced projection (spec: sync-status).
 *
 * **Only what the membership admits is counted.** The ledger holds a row for every resource this device
 * has stored for any event — the join-time load seeds them all — so counting the whole ledger would let a
 * device with many historical uploads read "in sync" while in-window photos were still pending. Counting
 * the gallery's admitted set makes `completed <= total` structural, counts a loaded (still undated) row as
 * done the moment `N` is counted, and asks the policy nothing on the 2 s poll: the set was derived when `N`
 * was.
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
 * [GalleryStatusSource]'s nullable admitted set and [LedgerCounts.read] — and cannot be satisfied by existing.
 *
 * A **counted** zero is a read value and does mint a snapshot: a non-contributing membership settles
 * the screen exactly as it always has.
 *
 * Each minted [SyncProgress] sets `completed` = the admitted photos the ledger has done, `total` = the
 * admitted set's size, `pending` = the admitted photos with a not-done row, still `min`-ed against the
 * remainder as a guard (display-only — see [SyncProgress]; on a healthy device it decides nothing) —
 * `active = (permission == GRANTED)`, `failed = 0`, and `estimatedRemaining = null`. Its fields stay
 * non-nullable: the un-read state is carried by [SyncStatus.Loading], never as a hole inside a
 * snapshot.
 *
 * Liveness is trigger-driven plus a foreground-gated poll: the [LedgerCountsSource] refreshes on
 * foreground entry, on each [StatusCountsPoller] tick while foregrounded (migration step 12 — the
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
            gallery.admitted,
        ) { counts, perm, admitted ->
            // The read gate. `admitted == null` is "the library was never enumerated" and `!counts.read`
            // is "the ledger was never read" — both distinct from the zeros they used to be seeded as.
            // Staying Loading here is what keeps the screen from claiming everything is shared before
            // anything has been looked at.
            if (admitted == null || !counts.read) return@combine SyncStatus.Loading
            val total = admitted.size
            val completedCount = admitted.count { it in counts.done }
            val remaining = (total - completedCount).coerceAtLeast(0)
            SyncStatus.Ready(
                SyncProgress(
                    // Ledger in-flight, clamped to remaining (display-only — see SyncProgress).
                    pending = minOf(admitted.count { it in counts.pending }, remaining),
                    completed = completedCount,
                    total = total,
                    failed = 0,
                    // Usable access: syncing is operational under both a full and a limited grant
                    // (capability `photo-access` — under LIMITED the total is selection-scoped).
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
