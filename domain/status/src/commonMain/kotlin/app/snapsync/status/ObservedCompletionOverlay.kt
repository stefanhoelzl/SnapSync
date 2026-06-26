package app.snapsync.status

import app.snapsync.engine.LedgerSnapshot
import kotlin.time.Instant

/**
 * The overlaid counts a [SyncProgress] is minted from: the ledger snapshot's completion count plus
 * the photos promoted by observation, the remaining backlog, and the ledger's completion timestamp
 * (the overlay never fabricates one).
 */
internal data class Overlaid(
    val completed: Int,
    val pending: Int,
    val newestCompletionAt: Instant?,
)

/**
 * Project the ledger [snapshot] through the [observed] succeeded keys: a pending photo is **promoted**
 * to complete when every one of its still-outstanding resource keys is observed. Pure — no clock, no
 * write. An empty [observed] yields `promoted = 0`, so the result equals the ledger snapshot
 * (`pendingByAsset.size` is exactly the ledger's pending-photo count). `state != COMPLETED` is already
 * baked into `pendingByAsset` (it holds only outstanding rows), so a stale `FAILED` key promotes like
 * a `REQUESTED` one.
 */
internal fun overlay(snapshot: LedgerSnapshot, observed: Set<String>): Overlaid {
    val promoted = snapshot.pendingByAsset.count { (_, keys) -> keys.all { it in observed } }
    return Overlaid(
        completed = snapshot.completed + promoted,
        pending = snapshot.pendingByAsset.size - promoted,
        newestCompletionAt = snapshot.newestCompletionAt,
    )
}

/**
 * Retain an observed key until the ledger snapshot confirms its photo is no longer outstanding, so a
 * key the platform releases (acknowledged) before the ledger ding arrives does not blink its photo
 * backward. `S' = (previous ∪ fresh) ∩ (keys still in the backlog)`: a key is dropped exactly when it
 * leaves `pendingByAsset` (recorded `COMPLETED` or pruned), at which point the snapshot's own
 * `completed` already covers it — and the retained set stays bounded by the backlog.
 */
internal fun stickyRetain(previous: Set<String>, fresh: Set<String>, snapshot: LedgerSnapshot): Set<String> {
    if (previous.isEmpty() && fresh.isEmpty()) return emptySet()
    val backlog = snapshot.pendingByAsset.values.flatMapTo(mutableSetOf()) { it }
    return (previous + fresh).intersect(backlog)
}
