package app.snapsync.share

import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Present the system share sheet (`UIActivityViewController`) carrying [text], from the current
 * top-most view controller — the UIKit half of the share command (an app-only adapter: presenting
 * UI is impossible in the extension process, and `UIApplication` is app-only API). Fire-and-forget:
 * no completion handler; the caller already holds the URL and `UiState` is unaffected.
 * iPhone-only/portrait, so no popover source is needed.
 *
 * The presenter walk (following `presentedViewController` to the top of the presentation stack) is
 * technology mechanics — UIKit rejects presentation from a covered controller — and adapters may
 * branch on technology vocabulary (spec `module-architecture`, "Ports are the I/O boundary named
 * for the need"); it drained here from the untested app shell at the migration finale.
 *
 * Marshalled onto the **main queue**: the container invokes the share command from an Orbit intent,
 * which runs on `Dispatchers.Default` (a background thread), and `UIActivityViewController`
 * presentation asserts the main queue (`dispatch_assert_queue`) — presenting off-main traps
 * (SIGTRAP).
 */
fun presentShareSheet(text: String) {
    dispatch_async(dispatch_get_main_queue()) {
        val activity = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
        var presenter = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (presenter?.presentedViewController != null) {
            presenter = presenter.presentedViewController
        }
        presenter?.presentViewController(activity, animated = true, completion = null)
    }
}
