package app.snapsync.gallery

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSISO8601DateFormatter
import platform.Photos.PHAsset
import platform.Photos.PHAssetResource
import platform.UniformTypeIdentifiers.UTType

/**
 * Synthesize the [AssetManifest] for a `PHAsset` from its **original** resources — the same originals,
 * keyed identically (via [resourceRole] + [uploadKey]), that [PhotoLibraryResourceEnumerator] uploads,
 * so a manifest's `filename`s match the stored objects. Returns `null` when the asset has no original
 * resource (nothing to back up, hence no manifest).
 *
 * Per resource: [ResourceRole] from the type, MIME `contentType` from the UTI, `filename` from
 * [uploadKey], and `originalFilename` from the resource. `creationDate` is the asset's capture
 * timestamp as ISO-8601 (empty when PhotoKit reports none). Wiring-only and untestable (PhotoKit);
 * the pure model + serialization it produces are unit-tested in `commonTest`.
 */
@OptIn(ExperimentalForeignApi::class)
fun assetManifest(asset: PHAsset): AssetManifest? {
    val assetId = asset.localIdentifier.replace('/', '_')
    val resources = mutableListOf<ManifestResource>()
    for (any in PHAssetResource.assetResourcesForAsset(asset)) {
        val resource = any as PHAssetResource
        val role = resourceRole(resource.type) ?: continue
        resources += ManifestResource(
            role = role,
            contentType = UTType.typeWithIdentifier(resource.uniformTypeIdentifier)?.preferredMIMEType
                ?: "application/octet-stream",
            filename = uploadKey(assetId, role, resource.originalFilename),
            originalFilename = resource.originalFilename,
        )
    }
    if (resources.isEmpty()) return null
    val creationDate = asset.creationDate?.let { NSISO8601DateFormatter().stringFromDate(it) } ?: ""
    return AssetManifest(
        version = ASSET_MANIFEST_VERSION,
        assetId = assetId,
        creationDate = creationDate,
        resources = resources,
    )
}
