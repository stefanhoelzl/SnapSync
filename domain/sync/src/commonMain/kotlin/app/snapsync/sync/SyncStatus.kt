package app.snapsync.sync

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Snapshot of the sync engine's current truth (design.md §2.3). Fields are demand-driven:
 * they exist only once a consumer renders them and a slice defines their semantics.
 *
 * Counts describe the most recent pass, in-flight or finished. [estimatedRemaining] is valid
 * as of the snapshot's emission: sources mint it per snapshot and never persist it (a stored
 * estimate is stale the moment it is written); `null` means not estimable. [active] is the
 * source's liveness verdict — how it is determined never crosses the seam.
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
     * Single source of truth for classifying a snapshot. Outcomes are yield-based —
     * an aborted pass is represented by the pass still being outstanding, not a verdict.
     * Branch order matters: it guarantees `lastFinishedAt != null` for all finished outcomes.
     */
    val state: SyncState
        get() = when {
            pending > 0 && active -> SyncState.IN_PROGRESS
            pending > 0 -> SyncState.SUSPENDED
            lastFinishedAt == null -> SyncState.NEVER_SYNCED
            failed == 0 -> SyncState.COMPLETE
            completed > 0 -> SyncState.INCOMPLETE
            else -> SyncState.FAILED
        }
}

enum class SyncState {
    NEVER_SYNCED,
    IN_PROGRESS,
    SUSPENDED,
    COMPLETE,
    INCOMPLETE,
    FAILED,
}
