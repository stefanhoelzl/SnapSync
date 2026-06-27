package app.snapsync.gallery

import app.snapsync.engine.Resource
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.timeIntervalSince1970
import platform.Photos.PHAsset
import platform.Photos.PHAssetResource
import platform.Photos.PHFetchResult

/**
 * The PhotoKit-backed [GalleryResourceEnumerator]: enumerates `PHAssetResource`s and derives each
 * resource's `(filename, assetId)` via the shared [uploadKey] derivation, carrying the
 * `PHAssetResource` itself as [Resource.data] so the producer can create a job from it.
 * This is the **single** PhotoKit resource-enumeration site — both the upload producer and the
 * re-join seed go through it, so their keys never diverge.
 *
 * Wiring-only and untestable (PhotoKit, device/simulator only); the pure derivation it calls is
 * unit-tested in `commonTest`, and [PhotoKitSmokeTest] confirms the enumeration glue runs on the sim.
 */
@OptIn(ExperimentalForeignApi::class)
class PhotoLibraryResourceEnumerator : GalleryResourceEnumerator {

    override suspend fun enumerate(): List<Resource> =
        resourcesForAssets(PHAsset.fetchAssetsWithOptions(null).localIdentifiers())

    override suspend fun resources(localIdentifiers: List<String>): List<Resource> =
        resourcesForAssets(localIdentifiers)

    private fun resourcesForAssets(localIdentifiers: List<String>): List<Resource> {
        if (localIdentifiers.isEmpty()) return emptyList()
        val assets = PHAsset.fetchAssetsWithLocalIdentifiers(localIdentifiers, null)
        val resources = mutableListOf<Resource>()
        var index = 0uL
        while (index < assets.count) {
            val asset = assets.objectAtIndex(index) as PHAsset
            index++
            val assetId = asset.localIdentifier.replace('/', '_')
            for (any in PHAssetResource.assetResourcesForAsset(asset)) {
                val resource = any as PHAssetResource
                resources += Resource(
                    filename = uploadKey(assetId, resource.type, resource.originalFilename),
                    assetId = assetId,
                    contentType = resource.uniformTypeIdentifier,
                    metadata = emptyMap(),
                    data = resource,
                )
            }
        }
        return resources
    }

    private fun PHFetchResult.localIdentifiers(): List<String> {
        val out = ArrayList<String>(count.toInt())
        var index = 0uL
        while (index < count) {
            out.add((objectAtIndex(index) as PHAsset).localIdentifier)
            index++
        }
        return out
    }
}
