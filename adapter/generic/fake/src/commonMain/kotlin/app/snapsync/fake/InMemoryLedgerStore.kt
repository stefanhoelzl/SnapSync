package app.snapsync.fake

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

/**
 * The honest in-memory [LedgerStore]: the dumbest possible row store. Mirrors the backend contract
 * exactly — verbatim storage, no interpretation, the record write's done-state guard (a settled row is
 * never overwritten, and a declined write does not ding), a ding after every write that changed it. The world
 * harness's real `SyncEngine`/`UploadCycle` write to this (it replaced the world's byte-identical
 * `WorldLedgerStore` copy at migration step 10, when this class moved out of `:domain:engine`'s
 * `commonTest` — a test source set no other module could depend on).
 */
internal class InMemoryLedgerStore : LedgerStore {

    private val rows = mutableMapOf<String, LedgerEntry>()

    private val dings = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val changes: Flow<Unit> = dings

    override suspend fun get(key: String): LedgerEntry? = rows[key]

    override suspend fun entryForDestination(destinationPath: String): LedgerEntry? =
        rows.values.firstOrNull { it.destinationPath == destinationPath }

    override suspend fun recordUnlessSettled(entry: LedgerEntry): Boolean {
        if (rows[entry.key]?.state?.isDone == true) return false
        rows[entry.key] = entry
        dings.tryEmit(Unit)
        return true
    }

    override suspend fun clear() {
        rows.clear()
        dings.tryEmit(Unit)
    }

    override suspend fun demoteRequested() {
        for (row in rows.entries) {
            if (row.value.state == LedgerState.REQUESTED) row.setValue(row.value.withState(LedgerState.DISCOVERED))
        }
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

    override suspend fun recordAllUnlessSettled(entries: List<LedgerEntry>): Int {
        // Build the next state fully before swapping — the SQL transaction's all-or-nothing, in memory.
        val next = rows.toMutableMap()
        var applied = 0
        for (entry in entries) {
            if (next[entry.key]?.state?.isDone == true) continue
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
        if (rows.keys.removeAll { it in wanted }) dings.tryEmit(Unit)
    }

    override suspend fun aggregates(): LedgerAggregates {
        // Counted by photo (assetId): a photo is complete only when all its rows are COMPLETED.
        val byAsset = rows.values.groupBy { it.assetId }
        val complete = byAsset.values.filter { group -> group.all { it.state.isDone } }
        return LedgerAggregates(
            pending = byAsset.size - complete.size,
            completed = complete.size,
        )
    }

    override suspend fun pendingResources(): List<PendingResource> =
        rows.values.filter { !it.state.isDone }
            .map { PendingResource(it.assetId, it.key) }

    override suspend fun manifestRows(): List<LedgerEntry> = rows.values.toList()

    override suspend fun backfillManifestDetail(entry: LedgerEntry) {
        val current = rows[entry.key] ?: return
        if (!current.needsManifestDetail) return // bare-only, exactly like the SQL UPDATE's WHERE
        rows[entry.key] = LedgerEntry(
            key = current.key,
            assetId = current.assetId,
            state = current.state,
            creationDate = entry.creationDate,
            role = entry.role,
            contentType = entry.contentType,
            originalFilename = entry.originalFilename,
            destinationPath = current.destinationPath, // the SQL UPDATE sets the four detail columns alone
        )
    }

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

    override suspend fun requestedKeys(): Set<String> =
        rows.values.filter { it.state == LedgerState.REQUESTED }.mapTo(mutableSetOf()) { it.key }
}
