package app.snapsync.gallery

import app.snapsync.engine.Resource
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.timeIntervalSince1970
import platform.Photos.PHAsset
import platform.Photos.PHAssetResource
import platform.Photos.PHFetchResult
import platform.UniformTypeIdentifiers.UTType

/**
 * The PhotoKit-backed [GalleryResourceEnumerator]: enumerates `PHAssetResource`s and derives each
 * resource's `(filename, assetId)` via the shared [uploadKey] derivation, carrying the
 * `PHAssetResource` itself as [Resource.data] so the producer can create a job from it, plus the
 * per-asset manifest detail (`creationDate`/`originalFilename`/MIME) in [Resource.metadata] so the
 * device manifest is built from this same enumeration (no second PhotoKit pass).
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
            // Per-asset capture timestamp (ISO-8601), reused for every resource of the asset — the
            // device-manifest detail, stashed in metadata so the manifest needs no second enumeration.
            val creationDate = asset.creationDate?.let { NSISO8601DateFormatter().stringFromDate(it) } ?: ""
            for (any in PHAssetResource.assetResourcesForAsset(asset)) {
                val resource = any as PHAssetResource
                // Originals only: a dropped (edit-artifact / RAW alternate / proxy) type has no role
                // and is never wrapped, so an asset's set is fixed at capture and never grows.
                val role = resourceRole(resource.type) ?: continue
                resources += Resource(
                    filename = uploadKey(assetId, role, resource.originalFilename),
                    assetId = assetId,
                    contentType = resource.uniformTypeIdentifier,
                    // Manifest detail (opaque to the engine): the device-manifest producer reads these
                    // to build entries from this same enumeration. MIME from the UTI; originals' name.
                    metadata = mapOf(
                        RESOURCE_META_CREATION_DATE to creationDate,
                        RESOURCE_META_ORIGINAL_FILENAME to resource.originalFilename,
                        RESOURCE_META_MIME to (
                            UTType.typeWithIdentifier(resource.uniformTypeIdentifier)?.preferredMIMEType
                                ?: "application/octet-stream"
                            ),
                    ),
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
