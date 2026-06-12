package app.snapsync.status

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Snapshot of backup truth, projected from the engine's ledger (design.md §2.4).
 *
 * Counts are lifetime aggregates over ledger keys: [pending] = keys not yet proven uploaded
 * (absent proof, `REQUESTED` hopes, transient `FAILED`), [completed] = keys with `COMPLETED`
 * proof. A re-upload flips its key back to pending — re-uploads are visible here.
 *
 * [failed] is structurally 0 from the ledger-backed source: retry-forever means no key is ever
 * given up on (an attempt budget would change that). The field exists for classification and
 * fakes; INCOMPLETE is harness-reachable only in v1.
 *
 * [active] is operational state — "the backup machinery is allowed to run" — derived from
 * permission, never from event recency. No clocks, no thresholds.
 *
 * [estimatedRemaining] is valid as of the snapshot's emission: sources mint it per snapshot and
 * never persist it (a stored estimate is stale the moment it is written); `null` means not
 * estimable — the v1 source always reports null.
 *
 * [lastFinishedAt] is the newest completion recorded in the ledger; `null` means nothing has
 * ever completed.
 */
data class SyncStatus(
    val pending: Int,
    val completed: Int,
    val failed: Int,
    val active: Boolean,
    val estimatedRemaining: Duration?,
    val lastFinishedAt: Instant?,
) {
    /**
     * Single source of truth for classifying a snapshot, in decision-table order: machinery off
     * outranks everything; outstanding work outranks history; the rest reads the ledger's
     * lifetime verdict. Branch order guarantees `lastFinishedAt != null` for INCOMPLETE and
     * COMPLETE. There is no FAILED state — retry-forever never gives up on a key, and "nothing
     * ever completed but something finished" is untellable when [lastFinishedAt] is the newest
     * completion.
     */
    val state: SyncState
        get() = when {
            !active -> SyncState.SUSPENDED
            pending > 0 -> SyncState.IN_PROGRESS
            lastFinishedAt == null -> SyncState.NEVER_SYNCED
            failed > 0 -> SyncState.INCOMPLETE
            else -> SyncState.COMPLETE
        }
}

enum class SyncState {
    NEVER_SYNCED,
    IN_PROGRESS,
    SUSPENDED,
    COMPLETE,
    INCOMPLETE,
}
