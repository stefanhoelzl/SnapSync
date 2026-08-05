package app.snapsync.permission

import app.snapsync.model.PermissionStatus
import app.snapsync.ports.PhotoAccessRequester
import app.snapsync.ports.PhotoAccessStatusSource

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSNotification
import app.snapsync.logging.invocation
import co.touchlab.kermit.Logger
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.NSObjectProtocol

/**
 * The iOS PhotoKit adapter — the first real platform implementation. One object implementing both
 * permission ports (the interface docs anticipate this).
 *
 * PhotoKit exposes the current authorization status **synchronously**, so the source seeds a real
 * value at construction (no Loading on the permission seam). It exposes **no** change observer, and
 * the user can flip access in system Settings while the app is backgrounded — so the adapter treats
 * the app returning to the foreground (`UIApplicationDidBecomeActiveNotification`) as a refresh
 * ding, re-reading the status. Status changes from a `request()` arrive via the same source.
 *
 * The mapping is faithful: `.authorized` → GRANTED (full library), `.limited` → LIMITED (the user's
 * hand-picked selection — a first-class working grant, capability `limited-photo-access`),
 * `.notDetermined` → NOT_DETERMINED, `.denied`/`.restricted` → DENIED. Access level is `.readWrite`
 * (PhotoKit has no read-only level; it is what discovery, resource reads, and imports need).
 *
 * Requires `NSPhotoLibraryUsageDescription` in the app's Info.plist, or `request()` traps.
 */
class PhotoLibraryPermission : PhotoAccessStatusSource, PhotoAccessRequester {

    private val state = MutableStateFlow(read())

    private val log = Logger.withTag("photoPermission")

    override val permission: StateFlow<PermissionStatus> = state

    // Block-based observer kept for the app's lifetime; the center retains it until removeObserver,
    // which v1 never calls (single app-lifetime adapter).
    @Suppress("unused")
    private val foregroundObserver: NSObjectProtocol =
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _: NSNotification? ->
            // PLATFORM ENTRY POINT (spec `diagnostic-logging`): the OS calls this observer body, so
            // it records that it was called and what it read. Once per foreground: INFO.
            log.invocation("photoPermission.onDidBecomeActive", result = { status: PermissionStatus -> "$status" }) {
                read().also { state.value = it }
            }
        }

    override fun request() {
        // Fire-and-forget: the result lands on the source via read(), per the port contract.
        PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelReadWrite) { _ ->
            state.value = read()
        }
    }

    override fun openSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        // `UIApplication` is main-thread-only and this adapter names the main lane itself rather than
        // inheriting its caller's — the same shape `IosShareSheet` and `PresentLimitedLibraryPicker`
        // already use, and the reason platform-UI adapters are the main lane's allowlist (law
        // "Dispatcher lanes are fixed by the composition"). The command that calls this is on the main
        // lane too; this makes the adapter correct for any caller rather than only that one.
        dispatch_async(dispatch_get_main_queue()) {
            UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
        }
    }

    private fun read(): PermissionStatus =
        when (PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)) {
            PHAuthorizationStatusAuthorized -> PermissionStatus.GRANTED
            PHAuthorizationStatusLimited -> PermissionStatus.LIMITED
            PHAuthorizationStatusNotDetermined -> PermissionStatus.NOT_DETERMINED
            // .denied, .restricted — refused or unchangeable.
            else -> PermissionStatus.DENIED
        }
}
