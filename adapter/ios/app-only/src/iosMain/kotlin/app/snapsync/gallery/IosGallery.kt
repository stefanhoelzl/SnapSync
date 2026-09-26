package app.snapsync.gallery

import app.snapsync.ios.discovery.libraryChangeTokenOf
import app.snapsync.ios.qos.photoKitReadLane
import app.snapsync.model.GalleryAccess
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.LibraryChangeToken
import kotlinx.coroutines.withContext
import platform.Photos.PHPhotoLibrary

/**
 * The app's PhotoKit [Gallery]: the shared [IosGalleryReader] every read and album write goes through, plus the
 * members only the foreground process has — the permission dialog and the limited-library picker (UIKit, through
 * [PhotoLibraryPermission]) and the library's change token.
 *
 * **App process only**, and placed by linkage to say so: this module is one the upload extension never links, so
 * the extension cannot hold a change token or a walk memo even by accident (capability `background-upload` — its
 * 32 MB limit leaves no room for a held walk).
 */
class IosGallery(
    private val reader: IosGalleryReader,
    private val permission: PhotoLibraryPermission,
) : Gallery, GalleryReader by reader {

    override suspend fun requestAccess(): GalleryAccess = permission.requestAccess()

    override suspend fun widenSelection(): GalleryAccess = permission.widenSelection()

    /**
     * `PHPhotoLibrary.currentChangeToken`, compared with `isEqual`. Not a cursor: never archived, stored, or handed
     * to `fetchPersistentChanges(since:)`. On [photoKitReadLane] because the read is a synchronous XPC round-trip
     * into `assetsd`; measured about 2 ms in darwinbg (SE2, iOS 26.6.2).
     */
    override suspend fun changeToken(): LibraryChangeToken? = withContext(photoKitReadLane) {
        libraryChangeTokenOf(PHPhotoLibrary.sharedPhotoLibrary().currentChangeToken)
    }
}
