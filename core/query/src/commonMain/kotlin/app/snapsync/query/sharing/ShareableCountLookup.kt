package app.snapsync.query.sharing

import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.data.sharing.PreviewRead
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.query.ScreenLookup
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The **shareable-count preview** the join and settings surfaces show while the member chooses a range: how many of
 * their own photos it would share — the same admission the upload takes ([OwnPhotos.preview]), counting what WILL be
 * shared, the photos waiting for the event's start among them. Purely local: no backend read.
 */
class ShareableCountLookup(private val photos: OwnPhotos, lane: CoroutineDispatcher) :
    ScreenLookup<CaptureRange, Int?>("query.shareableCount", lane) {

    /**
     * The count for the range, or `null` when no admitted set can be stated — no grant, an undecided one, or a partial
     * grant whose selection has not been read — which the surface shows as no row, never as a zero.
     */
    override suspend fun read(arg: CaptureRange): Int? =
        when (val read = photos.preview(arg)) {
            is PreviewRead.Counted -> read.photos
            PreviewRead.NotReadable -> null
        }
}
