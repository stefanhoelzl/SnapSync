package app.snapsync.world

import app.snapsync.model.LedgerAggregates
import app.snapsync.ports.LedgerStore
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.PendingResource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The world's in-memory [LedgerStore] — a byte-for-byte port of the engine's `commonTest`
 * `InMemoryLedgerStore` (which lives in `commonTest`, so a `commonMain` test-infra module cannot
 * depend on it). The dumbest possible row store: verbatim storage, last write wins, no interpretation,
 * a ding after every put. This is the real ledger the composed `SyncEngine`/`UploadCycle` write to.
 */
class WorldLedgerStore : LedgerStore {

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

    override suspend fun clearRequested() {
        rows.values.removeAll { it.state == LedgerState.REQUESTED }
        dings.tryEmit(Unit)
    }

    override suspend fun resetTo(entries: List<LedgerEntry>) {
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
