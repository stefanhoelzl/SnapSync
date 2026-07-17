package app.snapsync.upload

import app.snapsync.model.LedgerAggregates
import app.snapsync.model.LedgerBackend
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.PendingResource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** A minimal in-memory [LedgerBackend] test double — a last-write-wins map plus a put ding. */
class InMemoryLedgerBackend : LedgerBackend {

    private val entries = mutableMapOf<String, LedgerEntry>()
    private val dings = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val changes: Flow<Unit> = dings

    override suspend fun get(key: String): LedgerEntry? = entries[key]

    override suspend fun put(entry: LedgerEntry) {
        entries[entry.key] = entry
        dings.tryEmit(Unit)
    }

    override suspend fun clear() {
        entries.clear()
        dings.tryEmit(Unit)
    }

    override suspend fun clearRequested() {
        entries.values.removeAll { it.state == LedgerState.REQUESTED }
        dings.tryEmit(Unit)
    }

    override suspend fun resetTo(seed: List<LedgerEntry>) {
        val next = seed.associateByTo(mutableMapOf()) { it.key }
        entries.clear()
        entries.putAll(next)
        dings.tryEmit(Unit)
    }

    override suspend fun deleteByAssetId(assetId: String) {
        entries.values.removeAll { it.assetId == assetId }
        dings.tryEmit(Unit)
    }

    override suspend fun retainAssets(keep: Set<String>) {
        entries.values.removeAll { it.assetId !in keep }
        dings.tryEmit(Unit)
    }

    override suspend fun aggregates(): LedgerAggregates {
        // Counted by photo (assetId): a photo is complete only when all its rows are COMPLETED.
        val byAsset = entries.values.groupBy { it.assetId }
        val complete = byAsset.values.filter { group -> group.all { it.state == LedgerState.COMPLETED } }
        return LedgerAggregates(
            pending = byAsset.size - complete.size,
            completed = complete.size,
        )
    }

    override suspend fun pendingResources(): List<PendingResource> =
        entries.values.filter { it.state != LedgerState.COMPLETED }.map { PendingResource(it.assetId, it.key) }
}
