package app.snapsync.data.upload

import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.Stored
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.AssetProgress
import app.snapsync.domain.upload.LedgerState
import app.snapsync.ports.DbResult

/**
 * **How far this device's own photos have got**, per photo — done, handed over, or waiting: the per-asset collapse
 * the status line counts by, in one snapshot-consistent read. It interprets nothing about admission: the status
 * intersects it with the set it counts over.
 */
class UploadProgress(private val db: SharedDatabase) {

    /** Per photo of [event], how far it has got. */
    fun progress(event: EventId): ProgressRead = when (
        val read = db.execute { database ->
            database.ledgerQueries.assetProgress(LedgerState.done, LedgerState.handedOver, event.value) {
                    asset, notDone, handedOver ->
                Triple(asset, notDone, handedOver)
            }.executeAsList()
        }
    ) {
        is DbResult.Ok -> ProgressRead.Read(read.value.map(::progressOf).toMap())
        is DbResult.Failed -> ProgressRead.Unavailable(read.detail)
    }

    /** One photo's progress. */
    private fun progressOf(row: Triple<Stored<AssetId>, Long, Long>): Pair<AssetId, AssetProgress> =
        row.first.id() to AssetProgress.of(notDone = row.second, handedOver = row.third)
}

/** Per photo, how far it has got — or that the read could not be made. */
sealed interface ProgressRead {
    class Read(val photos: Map<AssetId, AssetProgress>) : ProgressRead

    data class Unavailable(val detail: String) : ProgressRead
}
