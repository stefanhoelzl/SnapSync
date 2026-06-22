package app.snapsync.gallery

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Photos.PHAsset
import platform.Photos.PHChange
import platform.Photos.PHPhotoLibrary
import platform.Photos.PHPhotoLibraryChangeObserverProtocol
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol

/**
 * The iOS PhotoKit adapter for the gallery size (`N`). Counts the **whole library**
 * (`PHAsset.fetchAssetsWithOptions(null)`), matching the extension's current unfiltered discovery —
 * when discovery later filters by capture date and media type, this fetch MUST adopt the same
 * predicate so `n` can converge to `N`.
 *
 * PhotoKit exposes the count synchronously, so the source seeds a real value at construction. It
 * re-reads on three dings, mirroring how the permission adapter handles invalidation:
 * - `photoLibraryDidChange` (a registered `PHPhotoLibraryChangeObserver`) — a photo added/removed,
 * - the app returning to the foreground (`UIApplicationDidBecomeActiveNotification`),
 * - [refresh], called by the composition root when an event is (re)joined.
 *
 * Wiring-only and untested (lives behind the `:domain:gallery` seam; the app constructs it).
 */
@OptIn(ExperimentalForeignApi::class)
class PhotoLibraryGalleryStatus : GalleryStatusSource {

    private val state = MutableStateFlow(read())

    override val size: StateFlow<Int> = state

    // A Kotlin interface and an Obj-C supertype cannot share a class, so the change observer is a
    // separate Obj-C object that calls back into refresh().
    private val changeObserver = LibraryChangeObserver { refresh() }

    @Suppress("unused")
    private val foregroundObserver: NSObjectProtocol =
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _: NSNotification? -> refresh() }

    init {
        PHPhotoLibrary.sharedPhotoLibrary().registerChangeObserver(changeObserver)
    }

    /** Re-read the library count and re-emit. Called on a (re)join (new event baseline). */
    fun refresh() {
        state.value = read()
    }

    private fun read(): Int = PHAsset.fetchAssetsWithOptions(null).count.toInt()
}

@OptIn(ExperimentalForeignApi::class)
private class LibraryChangeObserver(
    private val onChange: () -> Unit,
) : NSObject(), PHPhotoLibraryChangeObserverProtocol {
    override fun photoLibraryDidChange(changeInstance: PHChange) {
        onChange()
    }
}
