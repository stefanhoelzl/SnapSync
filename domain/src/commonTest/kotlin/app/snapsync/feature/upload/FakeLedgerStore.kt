package app.snapsync.feature.upload

import app.snapsync.model.LedgerAggregates
import app.snapsync.model.needsJob
import app.snapsync.model.isDone
import app.snapsync.ports.LedgerStore
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.PendingResource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** A minimal in-memory [LedgerStore] for the join tests (last-write-wins map, atomic resetTo). */
class FakeLedgerStore : LedgerStore {
    val rows = mutableMapOf<String, LedgerEntry>()
    private val dings = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val changes: Flow<Unit> = dings
    override suspend fun get(key: String): LedgerEntry? = rows[key]
    override suspend fun put(entry: LedgerEntry) { rows[entry.key] = entry; dings.tryEmit(Unit) }
    override suspend fun clear() { rows.clear(); dings.tryEmit(Unit) }
    override suspend fun clearRequested() { rows.values.removeAll { it.state == LedgerState.REQUESTED }; dings.tryEmit(Unit) }

    override suspend fun resetTo(entries: List<LedgerEntry>) {
        val next = entries.associateByTo(mutableMapOf()) { it.key }
        rows.clear(); rows.putAll(next); dings.tryEmit(Unit)
    }

    override suspend fun markAbsent(assetId: String) {
        for ((key, row) in rows) if (row.assetId == assetId && !row.absent) rows[key] = row.markedAbsent()
    }

    override suspend fun aggregates(): LedgerAggregates {
        val byAsset = rows.values.filterNot { it.absent }.groupBy { it.assetId }
        val complete = byAsset.values.filter { g -> g.all { it.state.isDone } }
        return LedgerAggregates(byAsset.size - complete.size, complete.size)
    }

    override suspend fun pendingResources(): List<PendingResource> =
        rows.values.filter { !it.state.isDone && !it.absent }
            .map { PendingResource(it.assetId, it.key) }

    override suspend fun backfillEventId(eventId: String) {
        for ((key, entry) in rows) {
            if (entry.eventId.isEmpty()) {
                rows[key] = LedgerEntry(entry.key, entry.assetId, entry.state, entry.attempt, eventId)
            }
        }
        dings.tryEmit(Unit)
    }

    override suspend fun completedManifestRows(): List<LedgerEntry> = emptyList()

    override suspend fun backfillManifestDetail(entry: LedgerEntry) = Unit

    override fun markTerminal(key: String, state: LedgerState): Boolean {
        val current = rows[key] ?: return false
        if (current.state != LedgerState.REQUESTED) return false
        // Every other column preserved, exactly as the targeted UPDATE preserves it — `absent` included.
        // A fake that re-stated only the columns it knew about would let a green suite hide a store that
        // silently resets a row's other facts at the moment an upload lands.
        rows[key] = LedgerEntry(
            key = current.key, assetId = current.assetId, state = state,
            attempt = current.attempt, eventId = current.eventId,
            creationDate = current.creationDate, role = current.role,
            contentType = current.contentType, originalFilename = current.originalFilename,
            absent = current.absent,
        )
        dings.tryEmit(Unit)
        return true
    }

    override suspend fun uploadedRows(): List<LedgerEntry> =
        rows.values.filter { it.state == LedgerState.UPLOADED }

    override suspend fun rowsNeedingJob(limit: Int): List<LedgerEntry> =
        rows.values.filter { it.state.needsJob && !it.absent }
            .sortedBy { it.key }
            .take(limit)

    override suspend fun requestedKeys(): Set<String> =
        rows.values.filter { it.state == LedgerState.REQUESTED }.mapTo(mutableSetOf()) { it.key }

    override suspend fun promoteUploaded(key: String): Boolean {
        val current = rows[key] ?: return false
        if (current.state != LedgerState.UPLOADED) return false
        // One field changes; every other fact about the row is carried over — see the port's KDoc.
        rows[key] = LedgerEntry(
            key = current.key, assetId = current.assetId, state = LedgerState.COMPLETED,
            attempt = current.attempt, eventId = current.eventId,
            creationDate = current.creationDate, role = current.role,
            contentType = current.contentType, originalFilename = current.originalFilename,
            absent = current.absent,
        )
        dings.tryEmit(Unit)
        return true
    }
}
