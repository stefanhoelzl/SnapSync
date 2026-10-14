@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.contract

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionChangeRequest
import platform.Photos.PHPhotoLibrary

// The member's own hand on the simulator's albums, for the clauses that need an album made or renamed as Photos
// makes and renames one: PhotoKit's collection change requests, the same changes the Photos app commits. Never a
// deletion — that raises a confirmation someone has to tap.

/** Creates a collection album titled [title], as any app with the full grant can; answers its `localIdentifier`. */
internal fun createCollection(title: String): String = memScoped {
    var created: String? = null
    val error = alloc<ObjCObjectVar<NSError?>>()
    PHPhotoLibrary.sharedPhotoLibrary().performChangesAndWait(
        changeBlock = {
            created = PHAssetCollectionChangeRequest.creationRequestForAssetCollectionWithTitle(title)
                .placeholderForCreatedAssetCollection.localIdentifier
        },
        error = error.ptr,
    )
    error.value?.let { error("creating album \"$title\" failed: ${it.domain} ${it.code} ${it.localizedDescription}") }
    checkNotNull(created) { "creating album \"$title\" named no collection" }
}

/** Renames the collection album [album] to [title], as the member does in Photos. */
internal fun renameCollection(album: String, title: String): Unit = memScoped {
    val collection = PHAssetCollection.fetchAssetCollectionsWithLocalIdentifiers(listOf(album), null)
        .firstObject as? PHAssetCollection ?: error("no album $album to rename")
    val error = alloc<ObjCObjectVar<NSError?>>()
    PHPhotoLibrary.sharedPhotoLibrary().performChangesAndWait(
        changeBlock = {
            checkNotNull(PHAssetCollectionChangeRequest.changeRequestForAssetCollection(collection)) {
                "album $album cannot be changed"
            }.setTitle(title)
        },
        error = error.ptr,
    )
    error.value?.let { error("renaming album $album failed: ${it.domain} ${it.code} ${it.localizedDescription}") }
}
