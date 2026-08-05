package app.snapsync.permission

import app.snapsync.logging.invocation
import app.snapsync.model.PlatformEntry
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity

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
    private val log: Logger = Logger.withTag("photoSelection"),
    // Last, so the single call site keeps its trailing-lambda form.
    private val onChange: (PHChange) -> Unit,
) : NSObject(), PHPhotoLibraryChangeObserverProtocol {

    fun register() {
        PHPhotoLibrary.sharedPhotoLibrary().registerChangeObserver(this)
    }

    fun unregister() {
        PHPhotoLibrary.sharedPhotoLibrary().unregisterChangeObserver(this)
    }

    // PLATFORM ENTRY POINT (spec `diagnostic-logging`). DEBUG on purpose: PhotoKit fires this on
    // EVERY library mutation, including each asset the download importer creates, so a 200-photo
    // import emits hundreds of these. At INFO they would flush the crash reporter's bounded
    // breadcrumb window and roll the size-capped device log before anyone read it.
    @PlatformEntry
    override fun photoLibraryDidChange(changeInstance: PHChange) =
        log.invocation("photoLibraryDidChange", severity = Severity.Debug) { onChange(changeInstance) }
}
