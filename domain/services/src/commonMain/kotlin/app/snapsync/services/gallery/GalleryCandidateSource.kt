package app.snapsync.services.gallery

import app.snapsync.model.CandidateRead
import app.snapsync.model.GalleryRead
import app.snapsync.model.ResourceBatch
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.candidatesFromFacts
import app.snapsync.model.resourcesFrom
import app.snapsync.ports.CandidateSource
import app.snapsync.ports.GalleryReader

/**
 * The library read for admission (capability `sync-status`), over the [GalleryReader]: facts now, and the
 * admitted assets' resources later, fetched together in one request ([resourceBatch]).
 *
 * [CandidateRead.NotReadable] only when the gallery itself cannot be read. Whether a partial grant's selection
 * may be read at all is decided one layer up, where the grant and the held snapshot are known
 * (`PermissionAwareCandidateSource`).
 */
class GalleryCandidateSource(private val gallery: GalleryReader) : CandidateSource {

    override suspend fun candidates(policy: SelectionPolicy): CandidateRead =
        when (val read = gallery.assets(policy)) {
            GalleryRead.NotReadable -> CandidateRead.NotReadable
            is GalleryRead.Read -> CandidateRead.Readable(candidatesFromFacts(read.value, resourceBatch(gallery)))
        }
}

/**
 * The resources of a set of assets through [gallery], mapped by the shared [resourcesFrom] (role filter,
 * upload-key derivation), keyed by asset id. An unreadable gallery reads no resources: a candidate it
 * produced then has none, which uploads nothing rather than something wrong.
 */
internal fun resourceBatch(gallery: GalleryReader): ResourceBatch = ResourceBatch { ids ->
    when (val read = gallery.resources(ids)) {
        GalleryRead.NotReadable -> emptyMap()
        is GalleryRead.Read -> resourcesFrom(read.value).groupBy { it.assetId }
    }
}
