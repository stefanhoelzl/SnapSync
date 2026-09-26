package app.snapsync.services.gallery

import app.snapsync.model.AssetId
import app.snapsync.model.AssetPresence
import app.snapsync.model.GalleryAccess
import app.snapsync.model.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.ImportedAssetPresence

/**
 * Whether assets this device created still exist, asked of the whole library (capability `receiving-photos`).
 *
 * **Only a full grant's miss is [AssetPresence.ABSENT].** Under a partial grant the gallery sees the selection,
 * and an asset created before a downgrade is real but invisible there; with no grant it sees nothing. Either
 * miss answered as absent would clear a live marker, import a second copy and orphan the first — so every other
 * grant answers [AssetPresence.UNKNOWN] and asks the gallery nothing. (The app composes the partial grant's
 * answer from its held selection snapshot instead: `PermissionAwareAssetPresence`.)
 */
class GalleryAssetPresence(private val gallery: GalleryReader) : ImportedAssetPresence {

    override suspend fun presence(localIds: Set<AssetId>): Map<AssetId, AssetPresence> {
        if (localIds.isEmpty()) return emptyMap()
        if (gallery.access() != GalleryAccess.GRANTED) return localIds.associateWith { AssetPresence.UNKNOWN }
        return when (val read = gallery.assetsById(localIds)) {
            GalleryRead.NotReadable -> localIds.associateWith { AssetPresence.UNKNOWN }
            is GalleryRead.Read -> {
                val present = read.value.mapTo(mutableSetOf()) { it.assetId }
                // Every id asked about gets an entry: a whole-library view may say ABSENT, and saying it
                // explicitly is what lets the caller clear a stale marker rather than leave the row stuck.
                localIds.associateWith { if (it in present) AssetPresence.PRESENT else AssetPresence.ABSENT }
            }
        }
    }
}
