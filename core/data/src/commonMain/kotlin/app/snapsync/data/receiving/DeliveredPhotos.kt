package app.snapsync.data.receiving

import app.snapsync.data.receiving.DownloadDatabaseFile.Companion.held
import app.snapsync.data.receiving.RowStatements.refsOf
import app.snapsync.data.receiving.RowStatements.valid
import app.snapsync.data.store.Stored
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.receiving.DeliveredPhoto
import app.snapsync.domain.time.Timestamp
import app.snapsync.format.TimestampText
import app.snapsync.wire.api.Limits

/**
 * **The received photos' delivery facts the service has not been told** (decision record `changes/delivery-record`,
 * D3): an event's photos added to the library since the last report the service recorded, oldest first — what the next
 * `received` report carries — and their marking once one has.
 */
class DeliveredPhotos(private val store: DownloadStore) {
    /** [event]'s untold photos, oldest first — at most as many as one report may carry (the rest go with the next). */
    fun untold(event: EventId): DownloadRead<List<DeliveredPhoto>> = store.read { q ->
        q.selectUnreportedDeliveries(
            valid(event),
            Limits.DELIVERED_MAX.toLong(),
            ::delivered,
        ).executeAsList().flatten()
    }

    /** [photos] were carried by a report the service recorded: none is told again. */
    fun told(photos: List<DeliveredPhoto>): DownloadRead<Unit> = store.read { q ->
        q.transaction {
            photos.forEach { q.markDeliveryReported(valid(it.ref.sourceDeviceId), valid(it.ref.sourceAssetId)) }
        }
    }

    /** A row as the report carries it — none for one whose ids or addition time do not read. */
    private fun delivered(
        device: Stored<DeviceId>,
        asset: Stored<AssetId>,
        downloaded: String?,
        added: String,
        by: String?,
    ): List<DeliveredPhoto> = refsOf(device.held() to asset.held()).mapNotNull { ref ->
        photo(ref, downloaded, TimestampText.parseOrNull(added), by)
    }

    /** None when its addition time does not read: a fact is never guessed. */
    private fun photo(ref: AssetRef, downloaded: String?, added: Timestamp?, by: String?): DeliveredPhoto? =
        when (added) {
            null -> null
            is Timestamp -> DeliveredPhoto(ref, downloaded(downloaded), added, by)
        }

    private fun downloaded(text: String?): Timestamp? = TimestampText.parseOrNull(text.orEmpty())
}
