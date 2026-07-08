@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.album

import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Photos.PHAsset
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionChangeRequest
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
