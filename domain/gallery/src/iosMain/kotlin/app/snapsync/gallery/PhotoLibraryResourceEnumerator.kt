package app.snapsync.gallery

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSISO8601DateFormatter
import platform.Photos.PHAsset
import platform.Photos.PHAssetResource
import platform.Photos.PHFetchResult
import platform.UniformTypeIdentifiers.UTType

/**
 * The PhotoKit-backed [RawAssetSource]: the **decision-free** library walk (capability `gallery-status`,
 * Move A). Fetches `PHAsset`s, reads each asset's **raw** `localIdentifier` + capture date, walks its
 * `PHAssetResource`s, and emits [RawResource]s carrying only raw facts — the raw `PHAssetResourceType`
 * value, the UTI, the iOS-resolved MIME, the original filename, and the opaque `PHAssetResource` handle.
 * It applies **no** role filter, key derivation, or `assetId` normalization — the shared [resourcesFrom]
 * mapping owns all of that, so the fan-out orchestration is unit-tested off-device.
 *
 * `UTType.preferredMIMEType` (UTI→MIME) stays **iOS-only** — Apple's UTI table must not be reimplemented
 * in `commonMain` — so the MIME is resolved here and carried out as a raw fact.
 *
 * Wiring-only and untestable (PhotoKit, device/simulator only); [PhotoKitSmokeTest] confirms this walk
 * glue runs on the simulator, and the pure mapping it feeds is unit-tested in `commonTest`.
 */
@OptIn(ExperimentalForeignApi::class)
class PhotoLibraryRawAssetSource : RawAssetSource {

    override suspend fun walkAll(): List<RawAsset> =
        walk(PHAsset.fetchAssetsWithOptions(null).localIdentifiers())

    override suspend fun walk(localIdentifiers: List<String>): List<RawAsset> {
        if (localIdentifiers.isEmpty()) return emptyList()
        val assets = PHAsset.fetchAssetsWithLocalIdentifiers(localIdentifiers, null)
        val out = mutableListOf<RawAsset>()
        var index = 0uL
        while (index < assets.count) {
            val asset = assets.objectAtIndex(index) as PHAsset
            index++
            // Per-asset capture timestamp (ISO-8601), reused for every resource of the asset.
            val creationDate = asset.creationDate?.let { NSISO8601DateFormatter().stringFromDate(it) } ?: ""
            val rawResources = PHAssetResource.assetResourcesForAsset(asset).map { any ->
                val resource = any as PHAssetResource
                RawResource(
                    type = resource.type, // raw PHAssetResourceType value — un-mapped
                    contentTypeUti = resource.uniformTypeIdentifier,
                    mimeContentType = UTType.typeWithIdentifier(resource.uniformTypeIdentifier)?.preferredMIMEType
                        ?: "application/octet-stream",
                    originalFilename = resource.originalFilename,
                    handle = resource, // opaque PHAssetResource, crosses uninterpreted
                )
            }
            // The RAW localIdentifier (with '/'); resourcesFrom normalizes it.
            out += RawAsset(assetId = asset.localIdentifier, creationDate = creationDate, rawResources = rawResources)
        }
        return out
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

/**
 * The iOS [GalleryResourceEnumerator]: the PhotoKit [PhotoLibraryRawAssetSource] walk composed with the
 * shared [resourcesFrom] mapping (via [ResourceEnumerator]). No-arg so the app and extension composition
 * roots keep constructing `PhotoLibraryResourceEnumerator()` unchanged. This is the **single** PhotoKit
 * resource-enumeration site — the upload producer and the re-join seed both go through it, so their keys
 * never diverge.
 */
class PhotoLibraryResourceEnumerator :
    GalleryResourceEnumerator by ResourceEnumerator(PhotoLibraryRawAssetSource())
