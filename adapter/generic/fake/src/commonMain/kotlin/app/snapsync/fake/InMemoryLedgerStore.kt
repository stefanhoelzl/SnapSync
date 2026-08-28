package app.snapsync.fake

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

/**
 * The honest in-memory [LedgerStore]: the dumbest possible row store. Mirrors the backend contract
 * exactly — verbatim storage, last write wins, no interpretation, a ding after every put. The world
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
        // Build the next state fully before swapping, so the replacement is atomic from any
        // collector's view (mirrors the SQL transaction) and a failure before the swap leaves the
        // store unchanged.
        val next = entries.associateByTo(mutableMapOf()) { it.key }
        rows.clear()
        rows.putAll(next)
        dings.tryEmit(Unit)
    }

    override suspend fun markAbsent(assetId: String) {
        for ((key, row) in rows) {
            if (row.assetId == assetId && !row.absent) rows[key] = row.markedAbsent()
        }
        dings.tryEmit(Unit)
    }

    override suspend fun aggregates(): LedgerAggregates {
        // Counted by photo (assetId): a photo is complete only when all its rows are COMPLETED.
        val byAsset = rows.values.filterNot { it.absent }.groupBy { it.assetId }
        val complete = byAsset.values.filter { group -> group.all { it.state.isDone } }
        return LedgerAggregates(
            pending = byAsset.size - complete.size,
            completed = complete.size,
        )
    }

    override suspend fun pendingResources(): List<PendingResource> =
        rows.values.filter { !it.state.isDone && !it.absent }
            .map { PendingResource(it.assetId, it.key) }

    override suspend fun backfillEventId(eventId: String) {
        // Rewrite only the '' pre-provenance sentinel, verbatim otherwise — mirrors the backend's
        // targeted UPDATE (last write wins, no interpretation).
        for ((key, entry) in rows) {
            if (entry.eventId.isEmpty()) {
                rows[key] = LedgerEntry(
                    key = entry.key,
                    assetId = entry.assetId,
                    state = entry.state,
                    attempt = entry.attempt,
                    eventId = eventId,
                    // "verbatim otherwise" includes the manifest detail: the targeted SQL UPDATE sets
                    // `eventId` alone, so a fake that dropped these would let a green suite hide a store
                    // that silently blanks the manifest on every cycle's sweep.
                    creationDate = entry.creationDate,
                    role = entry.role,
                    contentType = entry.contentType,
                    originalFilename = entry.originalFilename,
                )
            }
        }
        dings.tryEmit(Unit)
    }

    override suspend fun completedManifestRows(): List<LedgerEntry> =
        rows.values.filter { it.state.isDone && !it.needsManifestDetail && !it.absent }

    override suspend fun backfillManifestDetail(entry: LedgerEntry) {
        val current = rows[entry.key] ?: return
        if (!current.needsManifestDetail) return // bare-only, exactly like the SQL UPDATE's WHERE
        rows[entry.key] = LedgerEntry(
            key = current.key,
            assetId = current.assetId,
            state = current.state,
            attempt = current.attempt,
            eventId = current.eventId,
            creationDate = entry.creationDate,
            role = entry.role,
            contentType = entry.contentType,
            originalFilename = entry.originalFilename,
            absent = current.absent, // enriching detail never changes whether the asset is still here
        )
    }

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
