package app.snapsync.permission

import app.snapsync.gallery.currentPhotoPermission
import app.snapsync.logging.invocation
import app.snapsync.model.GalleryAccess
import app.snapsync.objc.objcBoundary
import app.snapsync.ports.PhotoAccessStatusSource
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHPhotoLibrary
import platform.PhotosUI.presentLimitedLibraryPickerFromViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The iOS photo-permission adapter: the permission status source, the Settings surface, and — for
 * [IosGallery] — the permission dialog and the limited-library picker, which both need UIKit.
 *
 * PhotoKit exposes the current authorization status **synchronously**, so the source seeds a real
 * value at construction (no Loading on the permission seam). It exposes **no** change observer, and
 * the user can flip access in system Settings while the app is backgrounded — so the adapter treats
 * the app returning to the foreground (`UIApplicationDidBecomeActiveNotification`) as a refresh
 * ding, re-reading the status. Status changes from [requestAccess] arrive via the same source.
 *
 * The mapping is faithful: `.authorized` → GRANTED (full library), `.limited` → LIMITED (the user's
 * hand-picked selection — a first-class working grant, capability `photo-access`),
 * `.notDetermined` → NOT_DETERMINED, `.denied`/`.restricted` → DENIED. Access level is `.readWrite`
 * (PhotoKit has no read-only level; it is what discovery, resource reads, and imports need).
 *
 * Requires `NSPhotoLibraryUsageDescription` in the app's Info.plist, or [requestAccess] traps.
 */
class PhotoLibraryPermission : PhotoAccessStatusSource {

    private val state = MutableStateFlow(read())

    private val log = Logger.withTag("photoPermission")

    override val permission: StateFlow<GalleryAccess> = state

    // Block-based observer kept for the app's lifetime; the center retains it until removeObserver,
    // which v1 never calls (single app-lifetime adapter).
    @Suppress("unused")
    private val foregroundObserver: NSObjectProtocol =
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _: NSNotification? ->
            // PLATFORM ENTRY POINT (spec `privacy-security`): the OS calls this observer body, so
            // it records that it was called and what it read. Once per foreground: INFO.
            objcBoundary(log, "photoPermission.onDidBecomeActive") {
                log.invocation("photoPermission.onDidBecomeActive", result = { status: GalleryAccess -> "$status" }) {
                    read().also { state.value = it }
                }
            }
        }

    /**
     * Ask for access and answer the grant that results; the same value lands on [permission]. With no screen
     * the dialog could present on — the app in the background — it asks nothing and answers the grant as it
     * stands (`Gallery.requestAccess`). The state read hops to the main queue, where UIKit answers it.
     */
    suspend fun requestAccess(): GalleryAccess = suspendCancellableCoroutine { cont ->
        dispatch_async(dispatch_get_main_queue()) {
            objcBoundary(log, "photoPermission.requestAccess") {
                if (UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateBackground) {
                    cont.resume(read())
                } else {
                    PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelReadWrite) { _ ->
                        objcBoundary(log, "photoPermission.request.completion") {
                            val now = read()
                            state.value = now
                            cont.resume(now)
                        }
                    }
                }
            }
        }
    }

    /**
     * PhotoKit's limited-library picker — the system sheet that lets a user with a **partial** grant
     * widen (or narrow) the set of photos this app can see (capability `photo-access`).
     *
     * This is the other half of `PHPhotoLibraryPreventAutomaticLimitedAccessAlert` in the app's
     * Info.plist: that key stops iOS auto-presenting its own "Select More Photos" alert on every
     * library touch (which, given SnapSync re-fetches per foreground and per reconcile, is an unusable
     * storm — observed on device 2026-07-20), and hands the app the duty of offering the picker itself.
     * Suppressing the alert WITHOUT this call would strand a limited user with no way to widen their
     * selection from inside the app.
     *
     * It lived beside this class as a top-level `presentLimitedLibraryPicker()` the composition root
     * passed as `AppPorts.presentPhotoPicker: () -> Unit` — a platform presentation handed to the core
     * behind a type that said nothing, and defaulted inert, so a composition that never wired it looked
     * exactly like one that had (`docs/architecture.md`, "Ports are the I/O boundary named for the
     * need"). It lives in this adapter because the same object already presents the permission dialog, and the
     * picker is the second face of that one need; [IosGallery] exposes it as `widenSelection`.
     *
     * Answers the grant once the picker is presented: PhotoKit reports the resulting selection through the
     * library change observer, never through a completion handler here.
     */
    suspend fun widenSelection(): GalleryAccess {
        presentPicker()
        return read()
    }

    private fun presentPicker() {
        // Same main-queue hop and presenter walk as `IosSystemUi.share`, for the same two
        // reasons: UIKit rejects presentation from a covered controller, and presentation asserts the
        // main queue (presenting off-main traps with SIGTRAP) while commands can arrive on any lane.
        dispatch_async(dispatch_get_main_queue()) { objcBoundary(log, "choosePhotos") {
            var presenter = UIApplication.sharedApplication.keyWindow?.rootViewController
            while (presenter?.presentedViewController != null) {
                presenter = presenter.presentedViewController
            }
            // Kotlin/Native exposes the ObjC selector `presentLimitedLibraryPickerFromViewController:`
            // (not Swift's `presentLimitedLibraryPicker(from:)`), and it lives in **PhotosUI** as a
            // category on PHPhotoLibrary — not in Photos, which is why PhotosUI is imported above and
            // why this can only ever be an app-only adapter.
            presenter?.let { PHPhotoLibrary.sharedPhotoLibrary().presentLimitedLibraryPickerFromViewController(it) }
        } }
    }

    // The one mapping, shared with the extension process (ext-safe `currentPhotoPermission`).
    private fun read(): GalleryAccess = currentPhotoPermission()
}
