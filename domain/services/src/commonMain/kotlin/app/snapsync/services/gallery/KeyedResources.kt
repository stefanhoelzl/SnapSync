package app.snapsync.services.gallery

import app.snapsync.data.sharing.DiscoveryRead
import app.snapsync.data.sharing.GalleryResources
import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.domain.sharing.AdmittedAsset
import app.snapsync.model.Resource
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader

/**
 * Ledger keys resolved to uploadable [Resource]s — **id-scoped, never a walk**. A row records that a resource needs
 * uploading but cannot carry the platform handle a job needs, so whoever creates a job from the ledger asks for exactly
 * the keys it means to send. A key is `<assetId>-<role>.<ext>`, so the asset is fetched by identifier and the matching
 * resource picked.
 *
 * **Partial-tolerant, and that is the contract.** A key whose asset has left resolves to nothing — the caller learns the
 * asset departed, a different fact from an upload failing. Each returned resource's `filename` is the key it was
 * resolved for. And **could not look is never gone**: a library that cannot be read now — no grant, or a partial grant
 * whose selection has not been read — answers [KeyedRead.NotReadable], and nothing is concluded from it (decision
 * record `changes/selection-is-the-walk`, D1). MIGRATING: the upload keys are the uploads' vocabulary, and move into the
 * layers with them.
 */
interface KeyedResources {
    suspend fun resourcesFor(keys: Set<String>): KeyedRead
}

/** What a resolve of ledger keys found. */
sealed interface KeyedRead {
    /** [key]'s live resource — none when its asset has left, or when the library cannot be read now. */
    fun liveResource(key: String): Resource?

    /** The keys' resources — a key whose asset has left resolves to nothing. */
    class Resolved(val resources: List<Resource>) : KeyedRead {
        override fun liveResource(key: String): Resource? = resources.firstOrNull { it.filename == key }
    }

    /** The library cannot be read now: nothing is known about any key. */
    data object NotReadable : KeyedRead {
        override fun liveResource(key: String): Resource? = null
    }
}

/** The keys resolved from the gallery itself — the app's own transfer session, outside any cycle. */
class GalleryKeyedResources(private val gallery: GalleryReader) : KeyedResources {
    override suspend fun resourcesFor(keys: Set<String>): KeyedRead =
        resolved(keys, GalleryResources(gallery).of(keys.mapTo(linkedSetOf(), ::assetIdFromUploadKey)))
}

/**
 * The keys resolved under the read the grant allows ([OwnPhotos.resolve]) — the upload cycle's, and its transfer's:
 * under a partial grant from the selection in hand, so a photo outside it is not this app's to upload.
 */
class ContributionKeyedResources(private val photos: OwnPhotos) : KeyedResources {
    override suspend fun resourcesFor(keys: Set<String>): KeyedRead =
        resolved(keys, photos.resolve(keys.mapTo(linkedSetOf(), ::assetIdFromUploadKey)))
}

/** Only the keys asked for come back: a Live Photo's paired video is never smuggled in beside its still. */
private fun resolved(keys: Set<String>, read: GalleryRead<List<GalleryAssetResources>>): KeyedRead = when (read) {
    GalleryRead.NotReadable -> KeyedRead.NotReadable
    is GalleryRead.Read -> KeyedRead.Resolved(resourcesFrom(read.value).filter { it.filename in keys })
}

/**
 * The uploadable resources of [assets], from the read that admitted them — in hand for a selection, one batched request
 * for a walk. A gallery that cannot be read now yields none, which uploads nothing rather than something wrong.
 */
suspend fun DiscoveryRead.Discovered.uploadable(assets: List<AdmittedAsset>): List<Resource> =
    when (val read = resourcesOf(assets)) {
        GalleryRead.NotReadable -> emptyList()
        is GalleryRead.Read -> resourcesFrom(read.value)
    }
