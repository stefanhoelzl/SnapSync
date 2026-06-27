package app.snapsync.engine

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Test seam double: the dumbest possible row store. Mirrors the backend contract exactly —
 * verbatim storage, last write wins, no interpretation, a ding after every put.
 */
class InMemoryLedgerBackend : LedgerBackend {

    private val rows = mutableMapOf<String, LedgerEntry>()

    private val dings = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val changes: Flow<Unit> = dings

    override suspend fun get(key: String): LedgerEntry? = rows[key]

    override suspend fun put(entry: LedgerEntry) {
        rows[entry.key] = entry
        dings.tryEmit(Unit)
    }

    override suspend fun clear() {
        rows.clear()
        dings.tryEmit(Unit)
    }

    override suspend fun resetTo(entries: List<LedgerEntry>) {
        // Build the next state fully before swapping, so the replacement is atomic from any
        // collector's view (mirrors the SQL transaction) and a failure before the swap leaves the
        // store unchanged.
        val next = entries.associateByTo(mutableMapOf()) { it.key }
        rows.clear()
        rows.putAll(next)
        dings.tryEmit(Unit)
    }

    override suspend fun deleteByAssetId(assetId: String) {
        rows.values.removeAll { it.assetId == assetId }
        dings.tryEmit(Unit)
    }

    override suspend fun retainAssets(keep: Set<String>) {
        rows.values.removeAll { it.assetId !in keep }
        dings.tryEmit(Unit)
    }

    override suspend fun aggregates(): LedgerAggregates {
        // Counted by photo (assetId): a photo is complete only when all its rows are COMPLETED.
        val byAsset = rows.values.groupBy { it.assetId }
        val complete = byAsset.values.filter { group -> group.all { it.state == LedgerState.COMPLETED } }
        return LedgerAggregates(
            pending = byAsset.size - complete.size,
            completed = complete.size,
        )
    }

    override suspend fun pendingResources(): List<PendingResource> =
        rows.values.filter { it.state != LedgerState.COMPLETED }.map { PendingResource(it.assetId, it.key) }
}
