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
 * App-only by construction: the observer protocol is Photos/PhotosUI surface reached through
 * `PHPhotoLibrary`, and the intended consumers ([presentLimitedLibraryPicker], the status walk) are
 * app-process only. This is the seed of the real `PhotoSelectionChangeSource` port adapter
 * (`LIMITED-ACCESS-DESIGN.md` §4); for the probe it drives a single logged walk.
 *
 * The instance must be retained by the caller — `PHPhotoLibrary` holds only a weak reference to
 * registered observers.
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
