package app.snapsync.gallery

import app.snapsync.ports.LibraryGrant
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHPhotoLibrary

/**
 * The current photo grant, in the shared vocabulary — the ONE `PHAuthorizationStatus` → [LibraryGrant]
 * mapping, read by both processes.
 *
 * It lives here, in the module the extension links, because the extension needs it too: its cycle withholds
 * under anything but a full grant, and the app-only permission adapter — which also presents the picker and opens
 * Settings — imports UIKit and PhotosUI, which the extension-safety gate forbids. `Photos` is allowed, so the
 * mapping moved and the app-only adapter delegates to it: one mapping, not a copy that could drift.
 *
 * A status read, never a request: it can present no dialog.
 */
fun currentPhotoPermission(): LibraryGrant =
    when (PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)) {
        PHAuthorizationStatusAuthorized -> LibraryGrant.GRANTED
        PHAuthorizationStatusLimited -> LibraryGrant.LIMITED
        PHAuthorizationStatusNotDetermined -> LibraryGrant.NOT_DETERMINED
        // .denied, .restricted — refused or unchangeable.
        else -> LibraryGrant.DENIED
    }
