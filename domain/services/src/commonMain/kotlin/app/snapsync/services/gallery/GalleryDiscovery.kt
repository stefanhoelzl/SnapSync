package app.snapsync.services.gallery

import app.snapsync.model.GalleryAccess
import app.snapsync.model.GalleryRead
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.candidatesFromFacts
import app.snapsync.model.resourcesFrom
import app.snapsync.ports.Discovery
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.UploadDiscovery

/**
 * The upload cycle's two library reads over the [GalleryReader] (capability `background-upload`): the
 * full-enumeration walk and the id-scoped resolve of ledger keys. Both tiers bind it; the app's wraps it in the
 * walk memo.
 *
 * **A walk is authoritative for deletion only under a full grant** ([Discovery.fullEnumeration]) — the one
 * decision here. Under a partial grant the gallery answers the selection, and a de-selected photo is not a
 * deleted one (capability `photo-access`); with no grant there is no library to have read. The cycle deletes
 * the in-window rows of every asset an authoritative walk did not return, so the grant is read BEFORE the walk:
 * a grant that narrows while the walk runs leaves this walk not authoritative rather than the reverse.
 */
class GalleryDiscovery(private val gallery: GalleryReader) : UploadDiscovery {

    override suspend fun discover(policy: SelectionPolicy): Discovery {
        val authoritative = gallery.access() == GalleryAccess.GRANTED
        return when (val read = gallery.assets(policy)) {
            GalleryRead.NotReadable -> Discovery(candidates = emptyList(), fullEnumeration = false)
            is GalleryRead.Read ->
                Discovery(candidatesFromFacts(read.value, resourceBatch(gallery)), fullEnumeration = authoritative)
        }
    }

    /**
     * One request for every asset the [keys] name; only the keys asked for come back, so a Live Photo's paired
     * video is never smuggled in beside a request for its still, and a key whose asset left the library simply
     * resolves to nothing (the port's partial contract).
     */
    override suspend fun resourcesFor(keys: Set<String>): List<Resource> {
        if (keys.isEmpty()) return emptyList()
        return when (val read = gallery.resources(keys.mapTo(linkedSetOf(), ::assetIdFromUploadKey))) {
            GalleryRead.NotReadable -> emptyList()
            is GalleryRead.Read -> resourcesFrom(read.value).filter { it.filename in keys }
        }
    }
}
