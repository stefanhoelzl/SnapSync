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

/** A minimal in-memory [LedgerStore] test double — a last-write-wins map plus a put ding. */
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

    override suspend fun markAbsent(assetId: String) {
        for ((key, row) in entries) {
            if (row.assetId == assetId && !row.absent) entries[key] = row.markedAbsent()
        }
        dings.tryEmit(Unit)
    }

    override suspend fun aggregates(): LedgerAggregates {
        // Counted by photo (assetId): a photo is complete only when all its rows are COMPLETED.
        val byAsset = entries.values.filterNot { it.absent }.groupBy { it.assetId }
        val complete = byAsset.values.filter { group -> group.all { it.state.isDone } }
        return LedgerAggregates(
            pending = byAsset.size - complete.size,
            completed = complete.size,
        )
    }

    override suspend fun pendingResources(): List<PendingResource> =
        entries.values.filter { !it.state.isDone && !it.absent }
            .map { PendingResource(it.assetId, it.key) }

    override suspend fun backfillEventId(eventId: String) {
        for ((key, entry) in entries) {
            if (entry.eventId.isEmpty()) {
                entries[key] = LedgerEntry(
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
        entries.values.filter { it.state.isDone && !it.needsManifestDetail && !it.absent }

    override suspend fun backfillManifestDetail(entry: LedgerEntry) {
        val current = entries[entry.key] ?: return
        if (!current.needsManifestDetail) return // bare-only, exactly like the SQL UPDATE's WHERE
        entries[entry.key] = LedgerEntry(
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
        val current = entries[key] ?: return false
        if (current.state != LedgerState.REQUESTED) return false
        entries[key] = LedgerEntry(
            key = current.key, assetId = current.assetId, state = state,
            attempt = current.attempt, eventId = current.eventId,
            creationDate = current.creationDate, role = current.role,
            contentType = current.contentType, originalFilename = current.originalFilename,
        )
        dings.tryEmit(Unit)
        return true
    }

    override suspend fun uploadedRows(): List<LedgerEntry> =
        entries.values.filter { it.state == LedgerState.UPLOADED }

    override suspend fun rowsNeedingJob(limit: Int): List<LedgerEntry> =
        entries.values.filter { it.state.needsJob && !it.absent }
            .sortedBy { it.key }
            .take(limit)

    override suspend fun requestedKeys(): Set<String> =
        entries.values.filter { it.state == LedgerState.REQUESTED }.mapTo(mutableSetOf()) { it.key }

    override suspend fun promoteUploaded(key: String): Boolean {
        val current = entries[key] ?: return false
        if (current.state != LedgerState.UPLOADED) return false
        // One field changes; every other fact about the row is carried over — see the port's KDoc.
        entries[key] = LedgerEntry(
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
