package app.snapsync.rejoin

import app.snapsync.engine.LedgerAggregates
import app.snapsync.engine.LedgerBackend
import app.snapsync.engine.LedgerEntry
import app.snapsync.engine.LedgerState
import app.snapsync.engine.PendingResource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** A minimal in-memory [LedgerBackend] for the join tests (last-write-wins map, atomic resetTo). */
class FakeLedgerBackend : LedgerBackend {
    val rows = mutableMapOf<String, LedgerEntry>()
    private val dings = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val changes: Flow<Unit> = dings
    override suspend fun get(key: String): LedgerEntry? = rows[key]
    override suspend fun put(entry: LedgerEntry) { rows[entry.key] = entry; dings.tryEmit(Unit) }
    override suspend fun clear() { rows.clear(); dings.tryEmit(Unit) }

    override suspend fun resetTo(entries: List<LedgerEntry>) {
        val next = entries.associateByTo(mutableMapOf()) { it.key }
        rows.clear(); rows.putAll(next); dings.tryEmit(Unit)
    }

    override suspend fun deleteByAssetId(assetId: String) { rows.values.removeAll { it.assetId == assetId } }
    override suspend fun retainAssets(keep: Set<String>) { rows.values.removeAll { it.assetId !in keep } }

    override suspend fun aggregates(): LedgerAggregates {
        val byAsset = rows.values.groupBy { it.assetId }
        val complete = byAsset.values.filter { g -> g.all { it.state == LedgerState.COMPLETED } }
        return LedgerAggregates(byAsset.size - complete.size, complete.size)
    }

    override suspend fun pendingResources(): List<PendingResource> =
        rows.values.filter { it.state != LedgerState.COMPLETED }.map { PendingResource(it.assetId, it.key) }
}
