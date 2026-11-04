package app.snapsync.data.upload

import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.Stored
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.LedgerState
import app.snapsync.ports.DbResult

/**
 * **How far this device's own photos have got**, per photo — whether every row of it is done: the per-asset
 * collapse the status line counts by, in one snapshot-consistent read. It interprets nothing about admission: the
 * status intersects it with the set it counts over. MIGRATING: the status's read models move in phase 4.9.
 */
class UploadProgress(private val db: SharedDatabase) {

    /** Per photo of [event], whether all its rows are done. */
    fun progress(event: EventId): ProgressRead = when (
        val read = db.execute { database ->
            database.ledgerQueries.assetProgress(LedgerState.done, event.value) { asset, notDone -> asset to notDone }
                .executeAsList()
        }
    ) {
        is DbResult.Ok -> ProgressRead.Read(read.value.mapNotNull(::progressOf).toMap())
        is DbResult.Failed -> ProgressRead.Unavailable(read.detail)
    }

    /** One photo's progress — none for an id column that spells no id: no photo of this device's is its. */
    private fun progressOf(row: Pair<Stored<AssetId>, Long>): Pair<AssetId, Boolean>? = when (val asset = row.first) {
        is Stored.Valid -> asset.value to (row.second == 0L)
        is Stored.Invalid -> null
    }
}

/** Per photo, whether all its rows are done — or that the read could not be made. */
sealed interface ProgressRead {
    class Read(val done: Map<AssetId, Boolean>) : ProgressRead

    data class Unavailable(val detail: String) : ProgressRead
}
