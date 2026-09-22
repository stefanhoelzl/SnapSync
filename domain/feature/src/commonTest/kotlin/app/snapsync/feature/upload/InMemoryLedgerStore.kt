package app.snapsync.feature.upload

import app.snapsync.model.LedgerAggregates
import app.snapsync.model.needsJob
import app.snapsync.model.isDone
import app.snapsync.ports.LedgerStore
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.TerminalOutcome
import app.snapsync.model.PendingResource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** A minimal in-memory [LedgerStore] test double — a map honouring the record guard, plus a ding per change. */
class InMemoryLedgerStore : LedgerStore {

    private val entries = mutableMapOf<String, LedgerEntry>()
    private val dings = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val changes: Flow<Unit> = dings

    override suspend fun get(key: String): LedgerEntry? = entries[key]


    override suspend fun entryForDestination(destinationPath: String): LedgerEntry? =
        entries.values.firstOrNull { it.destinationPath == destinationPath }

    override suspend fun recordUnlessSettled(entry: LedgerEntry): Boolean {
        if (entries[entry.key]?.state?.isDone == true) return false
        entries[entry.key] = entry
        dings.tryEmit(Unit)
        return true
    }

    override suspend fun clear() {
        entries.clear()
        dings.tryEmit(Unit)
    }

    override suspend fun resetTo(seed: List<LedgerEntry>) {
        val next = seed.associateByTo(mutableMapOf()) { it.key }
        entries.clear()
        entries.putAll(next)
        dings.tryEmit(Unit)
    }

    override suspend fun recordAllUnlessSettled(entries: List<LedgerEntry>): Int {
        val batch = entries
        // Build the next state fully before swapping — the SQL transaction's all-or-nothing, in memory.
        val next = this.entries.toMutableMap()
        var applied = 0
        for (entry in batch) {
            if (next[entry.key]?.state?.isDone == true) continue
            next[entry.key] = entry
            applied++
        }
        this.entries.clear()
        this.entries.putAll(next)
        if (applied > 0) dings.tryEmit(Unit)
        return applied
    }

    override suspend fun deleteKeys(keys: Collection<String>) {
        // Key-scoped, exactly like the backend's primary-key DELETE; dings only when a row went.
        val wanted = keys.toSet()
        if (entries.keys.removeAll { it in wanted }) dings.tryEmit(Unit)
    }

    override suspend fun aggregates(): LedgerAggregates {
        // Counted by photo (assetId): a photo is complete only when all its rows are COMPLETED.
        val byAsset = entries.values.groupBy { it.assetId }
        val complete = byAsset.values.filter { group -> group.all { it.state.isDone } }
        return LedgerAggregates(
            pending = byAsset.size - complete.size,
            completed = complete.size,
        )
    }


    override suspend fun assetProgress(): Map<String, Boolean> =
        entries.values.groupBy { it.assetId }.mapValues { (_, group) -> group.all { it.state.isDone } }
    override suspend fun pendingResources(): List<PendingResource> =
        entries.values.filter { !it.state.isDone }
            .map { PendingResource(it.assetId, it.key) }

    override suspend fun manifestRows(): List<LedgerEntry> = entries.values.toList()

    override suspend fun backfillManifestDetail(entry: LedgerEntry) {
        val current = entries[entry.key] ?: return
        if (!current.needsManifestDetail) return // bare-only, exactly like the SQL UPDATE's WHERE
        entries[entry.key] = LedgerEntry(
            key = current.key,
            assetId = current.assetId,
            state = current.state,
            creationDate = entry.creationDate,
            role = entry.role,
            contentType = entry.contentType,
            originalFilename = entry.originalFilename,
            destinationPath = current.destinationPath,
        )
    }

    override fun markTerminal(key: String, outcome: TerminalOutcome): Boolean {
        val current = entries[key] ?: return false
        if (current.state != LedgerState.REQUESTED) return false
        entries[key] = current.withState(outcome.state)
        dings.tryEmit(Unit)
        return true
    }

    override suspend fun rowsNeedingJob(): List<LedgerEntry> =
        entries.values.filter { it.state.needsJob }
            .sortedBy { it.key }
}
