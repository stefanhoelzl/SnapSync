package app.snapsync.query.sharing

import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.data.sharing.PreviewRead
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.sharing.CaptureRange

/**
 * The **shareable-count preview** the join and settings surfaces show while the member chooses a range: how many of
 * their own photos it would share — the same admission the upload takes ([OwnPhotos.preview]), counting what WILL be
 * shared, the photos waiting for the event's start among them. Purely local: no backend read.
 */
class ShareableCountQuery(private val photos: OwnPhotos) {

    /**
     * The count for [range], or `null` when no admitted set can be stated — no grant, an undecided one, or a partial
     * grant whose selection has not been read — which the surface shows as no row, never as a zero. [received] is echo
     * suppression's set (MIGRATING: its store is still the old zones').
     */
    suspend fun count(range: CaptureRange, received: Set<AssetId>): Int? =
        when (val read = photos.preview(range, received)) {
            is PreviewRead.Counted -> read.photos
            PreviewRead.NotReadable -> null
        }
}
