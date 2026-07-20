package app.snapsync.permission

import platform.Photos.PHChange
import platform.Photos.PHPhotoLibrary
import platform.Photos.PHPhotoLibraryChangeObserverProtocol
import platform.darwin.NSObject

/**
 * Registers a `PHPhotoLibraryChangeObserver` and invokes [onChange] whenever the photo library — or,
 * under a limited grant, the user's **selection** — changes (via our in-app picker, the Settings
 * editor, or iCloud sync).
 *
 * App-only by construction: the observer protocol is Photos surface reached through
 * `PHPhotoLibrary`, and its one consumer ([PhotoSelectionSnapshotSource], capability
 * `limited-photo-access`) is app-process only.
 *
 * The instance must be retained by the caller — `PHPhotoLibrary` holds only a weak reference to
 * registered observers. The callback arrives on a PhotoKit-owned background queue; the consumer owns
 * the hop.
 */
class PhotoSelectionObserver(
    private val onChange: (PHChange) -> Unit,
) : NSObject(), PHPhotoLibraryChangeObserverProtocol {

    fun register() {
        PHPhotoLibrary.sharedPhotoLibrary().registerChangeObserver(this)
    }

    fun unregister() {
        PHPhotoLibrary.sharedPhotoLibrary().unregisterChangeObserver(this)
    }

    override fun photoLibraryDidChange(changeInstance: PHChange) {
        onChange(changeInstance)
    }
}
