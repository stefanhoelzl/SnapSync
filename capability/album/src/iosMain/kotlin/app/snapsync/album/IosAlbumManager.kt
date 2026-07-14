@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.album

import app.snapsync.gallery.normalizeAssetId
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSISO8601DateFormatWithFractionalSeconds
import platform.Foundation.NSISO8601DateFormatWithInternetDateTime
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSPredicate
import platform.Photos.PHAsset
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionChangeRequest
import platform.Photos.PHAssetCollectionSubtypeAlbumRegular
import platform.Photos.PHAssetCollectionTypeAlbum
import platform.Photos.PHFetchOptions
import platform.Photos.PHPhotoLibrary

/**
 * The iOS [AlbumManager] (capability `event-album`): the only place that touches
 * `PHAssetCollectionChangeRequest`. Wiring-only and untested (the orchestration in [AlbumCoordinator] is
 * tested with a fake); the extension-process ability to create + mutate a `PHAssetCollection` is verified
 * on device. All calls use the synchronous `performChangesAndWait` (mirroring `IosPhotoKitUploadPlatform`)
 * so they run under the extension's/app's blocking cycle.
 */
class IosAlbumManager(
    private val log: Logger = Logger.withTag("IosAlbumManager"),
) : AlbumManager {

    override suspend fun ensureCreated(name: String): String? = memScoped {
        var placeholderId: String? = null
        val errorVar = alloc<ObjCObjectVar<NSError?>>()
        PHPhotoLibrary.sharedPhotoLibrary().performChangesAndWait(
            changeBlock = {
                val req = PHAssetCollectionChangeRequest.creationRequestForAssetCollectionWithTitle(name)
                placeholderId = req.placeholderForCreatedAssetCollection.localIdentifier
            },
            error = errorVar.ptr,
        )
        val error = errorVar.value
        if (error != null) {
            log.w { "ensureCreated failed: code=${error.code} ${error.localizedDescription}" }
            null
        } else {
            placeholderId
        }
    }

    override suspend fun exists(albumLocalId: String): Boolean {
        val result = PHAssetCollection.fetchAssetCollectionsWithLocalIdentifiers(listOf(albumLocalId), null)
        return result.count > 0uL
    }

    /**
     * Decision-free membership lookup (capability `photo-selection-policy`). Walks the device's **user**
     * albums once, keeps those whose title is in [titles], and fetches each one's members bounded by [since].
     *
     * Cost is O(albums) — the album list is small and the per-album member fetch is a single call. It is
     * deliberately *not* a per-asset membership test, which would reintroduce exactly the per-asset
     * round-trip cost the bounded walk exists to avoid.
     *
     * Smart albums are not consulted: their titles are system-localized, so matching one by title is
     * meaningless. (Screenshots are excluded by *subtype*, upstream, and never reach this seam.)
     *
     * An unparseable [since] drops the member-fetch bound rather than fetching nothing — under-returning here
     * would silently *admit* a denylisted photo, which is the safe direction, but fetching nothing at all
     * would make the whole rule a no-op.
     */
    override suspend fun assetIdsInAlbums(titles: Set<String>, since: String): Set<String> {
        if (titles.isEmpty()) return emptySet()
        val albums = PHAssetCollection.fetchAssetCollectionsWithType(
            PHAssetCollectionTypeAlbum,
            subtype = PHAssetCollectionSubtypeAlbumRegular,
            options = null,
        )
        val bound = parseSince(since)
        val out = mutableSetOf<String>()
        var i = 0uL
        while (i < albums.count) {
            val album = albums.objectAtIndex(i) as PHAssetCollection
            i++
            val title = album.localizedTitle ?: continue
            if (titles.none { it.equals(title.trim(), ignoreCase = true) }) continue

            val options = bound?.let {
                PHFetchOptions().apply {
                    predicate = NSPredicate.predicateWithFormat("creationDate >= %@", argumentArray = listOf(it))
                }
            }
            val assets = PHAsset.fetchAssetsInAssetCollection(album, options)
            var j = 0uL
            while (j < assets.count) {
                val asset = assets.objectAtIndex(j) as PHAsset
                j++
                // Normalized ('/'→'_') so it matches the assetIds the upload cycle filters on.
                out += normalizeAssetId(asset.localIdentifier)
            }
            log.i { "denylisted album '$title': ${assets.count} member(s) in scope" }
        }
        return out
    }

    private fun parseSince(since: String): NSDate? {
        NSISO8601DateFormatter().dateFromString(since)?.let { return it }
        return NSISO8601DateFormatter().apply {
            formatOptions = NSISO8601DateFormatWithInternetDateTime or NSISO8601DateFormatWithFractionalSeconds
        }.dateFromString(since)
    }

    override suspend fun add(albumLocalId: String, rawLocalIds: List<String>) {
        if (rawLocalIds.isEmpty()) return
        val collection = PHAssetCollection
            .fetchAssetCollectionsWithLocalIdentifiers(listOf(albumLocalId), null)
            .firstObject as? PHAssetCollection
        if (collection == null) {
            log.w { "add: album $albumLocalId no longer resolves — skipping ${rawLocalIds.size}" }
            return
        }
        val assets = PHAsset.fetchAssetsWithLocalIdentifiers(rawLocalIds, null)
        if (assets.count == 0uL) {
            log.i { "add: none of ${rawLocalIds.size} localIds resolve to assets — nothing to add" }
            return
        }
        memScoped {
            val errorVar = alloc<ObjCObjectVar<NSError?>>()
            PHPhotoLibrary.sharedPhotoLibrary().performChangesAndWait(
                changeBlock = {
                    // PHFetchResult conforms to NSFastEnumeration, so it is a valid addAssets argument.
                    PHAssetCollectionChangeRequest.changeRequestForAssetCollection(collection)?.addAssets(assets)
                },
                error = errorVar.ptr,
            )
            errorVar.value?.let { log.w { "add commit failed: code=${it.code} ${it.localizedDescription}" } }
        }
    }
}
