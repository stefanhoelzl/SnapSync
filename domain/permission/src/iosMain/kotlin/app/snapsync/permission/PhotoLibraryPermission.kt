package app.snapsync.permission

import app.snapsync.model.PermissionStatus
import app.snapsync.ports.PermissionRequester
import app.snapsync.ports.PermissionStatusSource

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIApplication
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
 * v1 requires a **full** grant: `.limited`/`.denied`/`.restricted` all map to DENIED (so the screen
 * can never report "complete" over a partially-readable library); `.authorized` → GRANTED,
 * `.notDetermined` → NOT_DETERMINED. Access level is `.readWrite` (PhotoKit has no read-only level;
 * full-library access is what discovery and resource reads need).
 *
 * Requires `NSPhotoLibraryUsageDescription` in the app's Info.plist, or `request()` traps.
 */
class PhotoLibraryPermission : PermissionStatusSource, PermissionRequester {

    private val state = MutableStateFlow(read())

    override val permission: StateFlow<PermissionStatus> = state

    // Block-based observer kept for the app's lifetime; the center retains it until removeObserver,
    // which v1 never calls (single app-lifetime adapter).
    @Suppress("unused")
    private val foregroundObserver: NSObjectProtocol =
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _: NSNotification? -> state.value = read() }

    override fun request() {
        // Fire-and-forget: the result lands on the source via read(), per the port contract.
        PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelReadWrite) { _ ->
            state.value = read()
        }
    }

    override fun openSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    private fun read(): PermissionStatus =
        when (PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)) {
            PHAuthorizationStatusAuthorized -> PermissionStatus.GRANTED
            PHAuthorizationStatusNotDetermined -> PermissionStatus.NOT_DETERMINED
            // .limited, .denied, .restricted — anything short of full access gates the screen.
            else -> PermissionStatus.DENIED
        }
}
