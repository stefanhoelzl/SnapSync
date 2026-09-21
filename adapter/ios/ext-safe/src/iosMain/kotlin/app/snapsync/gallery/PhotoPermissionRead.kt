package app.snapsync.gallery

import app.snapsync.model.PermissionStatus
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHPhotoLibrary

/**
 * The current photo grant, in the shared vocabulary — the ONE `PHAuthorizationStatus` → [PermissionStatus]
 * mapping, read by both processes.
 *
 * It lives here, in the module the extension links, because the extension needs it too: its cycle withholds
 * under anything but a full grant (capability `ios-photokit-upload`, "The extension withholds its cycle without
 * a full grant"), and the app-only permission adapter — which also presents the picker and opens Settings —
 * imports UIKit and PhotosUI, which the extension-safety gate forbids. `Photos` is allowed, so the mapping moved
 * and the app-only adapter delegates to it: one mapping, not a copy that could drift.
 *
 * A status read, never a request: it can present no dialog.
 */
fun currentPhotoPermission(): PermissionStatus =
    when (PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)) {
        PHAuthorizationStatusAuthorized -> PermissionStatus.GRANTED
        PHAuthorizationStatusLimited -> PermissionStatus.LIMITED
        PHAuthorizationStatusNotDetermined -> PermissionStatus.NOT_DETERMINED
        // .denied, .restricted — refused or unchangeable.
        else -> PermissionStatus.DENIED
    }
