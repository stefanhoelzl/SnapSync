package app.snapsync.feature.upload

import app.snapsync.model.AssetId
import app.snapsync.model.LedgerAggregates
import app.snapsync.model.needsJob
import app.snapsync.model.isDone
import app.snapsync.ports.LedgerStore
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.TerminalOutcome
import app.snapsync.model.PendingResource
import app.snapsync.model.changesManifestProjection
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** A minimal in-memory [LedgerStore] for the join tests (guarded record writes, atomic resetTo). */
class FakeLedgerStore : LedgerStore {
    val rows = mutableMapOf<String, LedgerEntry>()
    private val dings = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val changes: Flow<Unit> = dings
    override suspend fun get(key: String): LedgerEntry? = rows[key]

    override suspend fun entryForDestination(destinationPath: String): LedgerEntry? =
        rows.values.firstOrNull { it.destinationPath == destinationPath }
    override suspend fun recordUnlessSettled(entry: LedgerEntry): Boolean {
        if (rows[entry.key]?.state?.isDone == true) return false
        advance(rows[entry.key], entry)
        rows[entry.key] = entry; dings.tryEmit(Unit)
        return true
    }
    override suspend fun clear() { version += rows.size; rows.clear(); dings.tryEmit(Unit) }
    override suspend fun resetTo(entries: List<LedgerEntry>) {
        val next = entries.associateByTo(mutableMapOf()) { it.key }
        version += rows.size + next.size
        rows.clear(); rows.putAll(next); dings.tryEmit(Unit)
    }

    override suspend fun recordAllUnlessSettled(entries: List<LedgerEntry>): Int {
        // Build the next state fully before swapping — the SQL transaction's all-or-nothing, in memory.
        val next = rows.toMutableMap()
        var applied = 0
        for (entry in entries) {
            if (next[entry.key]?.state?.isDone == true) continue
            advance(next[entry.key], entry)
            next[entry.key] = entry
            applied++
        }
        rows.clear()
        rows.putAll(next)
        if (applied > 0) dings.tryEmit(Unit)
        return applied
    }

    override suspend fun deleteKeys(keys: Collection<String>) {
        // Key-scoped, exactly like the backend's primary-key DELETE; dings only when a row went.
        val wanted = keys.toSet()
        val before = rows.size
        if (rows.keys.removeAll { it in wanted }) dings.tryEmit(Unit)
        version += before - rows.size
    }

    override suspend fun aggregates(): LedgerAggregates {
        val byAsset = rows.values.groupBy { it.assetId }
        val complete = byAsset.values.filter { g -> g.all { it.state.isDone } }
        return LedgerAggregates(byAsset.size - complete.size, complete.size)
    }


    override suspend fun assetProgress(): Map<AssetId, Boolean> =
        rows.values.groupBy { it.assetId }.mapValues { (_, group) -> group.all { it.state.isDone } }
    override suspend fun pendingResources(): List<PendingResource> =
        rows.values.filter { !it.state.isDone }
            .map { PendingResource(it.assetId, it.key) }

    override suspend fun manifestRows(): List<LedgerEntry> = emptyList()

    override suspend fun backfillManifestDetail(entry: LedgerEntry) = Unit

    override fun markTerminal(key: String, outcome: TerminalOutcome): Boolean {
        val current = rows[key] ?: return false
        if (current.state != LedgerState.REQUESTED) return false
        // Every other column preserved, exactly as the targeted UPDATE preserves it. A fake that re-stated
        // only the columns it knew about would let a green suite hide a store that silently resets a row's
        // other facts at the moment an upload lands.
        rows[key] = current.withState(outcome.state)
        dings.tryEmit(Unit)
        return true
    }

    override suspend fun rowsNeedingJob(): List<LedgerEntry> =
        rows.values.filter { it.state.needsJob }
            .sortedBy { it.key }

    // The manifest version, advanced by the rule the SQLite store's triggers apply
    // (`changesManifestProjection`): every insert and delete, and a change to a projected column.
    private var version = 0L

    private fun advance(before: LedgerEntry?, after: LedgerEntry?) {
        if (changesManifestProjection(before, after)) version++
    }

    override suspend fun manifestVersion(): Long = version

    override suspend fun bumpManifestVersion() {
        version++
    }
}
