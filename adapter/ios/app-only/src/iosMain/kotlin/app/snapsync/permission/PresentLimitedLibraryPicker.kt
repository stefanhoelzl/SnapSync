package app.snapsync.permission

import platform.Photos.PHPhotoLibrary
import platform.PhotosUI.presentLimitedLibraryPickerFromViewController
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Present PhotoKit's limited-library picker — the system sheet that lets a user with a **partial**
 * grant widen (or narrow) the set of photos this app can see.
 *
 * App-only by construction: `UIApplication` and view-controller presentation do not exist in the
 * extension process, so this cannot be linked there (the extension-safety gate in
 * `:test:architecture` forbids `platform.UIKit` in extension-linked source). It sits beside
 * [PhotoLibraryPermission] rather than in `share/` because it is part of the permission surface.
 *
 * This is the other half of `PHPhotoLibraryPreventAutomaticLimitedAccessAlert` in the app's
 * Info.plist: that key stops iOS auto-presenting its own "Select More Photos" alert on every
 * library touch (which, given SnapSync re-fetches per foreground and per reconcile, is an
 * unusable storm — observed on device 2026-07-20), and hands the app the duty of offering the
 * picker itself. Suppressing the alert WITHOUT this call would strand a limited user with no way
 * to widen their selection from inside the app.
 *
 * The presenter walk and the main-queue hop mirror `presentShareSheet` for the same two reasons:
 * UIKit rejects presentation from a covered controller, and presentation asserts the main queue
 * (presenting off-main traps with SIGTRAP) while commands arrive on `Dispatchers.Default`.
 *
 * Fire-and-forget: PhotoKit reports the resulting selection through the library change observer,
 * not through a completion handler here, so there is no result to return.
 */
fun presentLimitedLibraryPicker() {
    dispatch_async(dispatch_get_main_queue()) {
        var presenter = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (presenter?.presentedViewController != null) {
            presenter = presenter.presentedViewController
        }
        // Kotlin/Native exposes the ObjC selector `presentLimitedLibraryPickerFromViewController:`
        // (not Swift's `presentLimitedLibraryPicker(from:)`), and it lives in **PhotosUI** as a
        // category on PHPhotoLibrary — not in Photos, which is why PhotosUI is imported above and
        // why this can only ever be an app-only adapter.
        presenter?.let { PHPhotoLibrary.sharedPhotoLibrary().presentLimitedLibraryPickerFromViewController(it) }
    }
}
