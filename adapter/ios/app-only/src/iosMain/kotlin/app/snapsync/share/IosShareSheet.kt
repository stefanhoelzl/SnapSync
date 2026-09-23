package app.snapsync.share

import app.snapsync.ports.Handoff
import app.snapsync.ports.SharePresenter
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The iOS [SharePresenter]: presents the system share sheet (`UIActivityViewController`) carrying the
 * text, from the current top-most view controller — an app-only adapter (presenting UI is impossible in
 * the extension process, and `UIApplication` is app-only API). iPhone-only / portrait, so no popover
 * source is needed.
 *
 * It answers [Handoff.Accepted] once UIKit reports the sheet presented, and [Handoff.Refused] when there is
 * no key window to present from — the tap then does nothing on screen. What the user picks in the sheet
 * is not observed at all (the port says why).
 *
 * It was a top-level `presentShareSheet(text)` function the composition root passed as
 * `AppPorts.share: (String) -> Unit`. A function reference is not a port: nothing about
 * `(String) -> Unit` distinguished "presents system UI" from in-core coordination, and the gates that
 * inspect types therefore saw a platform touch cross into the core unremarked (spec
 * `module-architecture`, "Ports are the I/O boundary named for the need").
 *
 * The presenter walk (following `presentedViewController` to the top of the presentation stack) is
 * technology mechanics — UIKit rejects presentation from a covered controller — and adapters may
 * branch on technology vocabulary (same spec); it drained here from the untested app shell at the
 * migration finale.
 *
 * Marshalled onto the **main queue**: the container invokes the share command from an Orbit intent,
 * which runs on `Dispatchers.Default` (a background thread), and `UIActivityViewController`
 * presentation asserts the main queue (`dispatch_assert_queue`) — presenting off-main traps
 * (SIGTRAP). The adapter names the lane itself so it is correct for any caller, not only the one
 * command that is declared on the main lane.
 */
class IosShareSheet : SharePresenter {

    override suspend fun share(text: String): Handoff = suspendCoroutine { done ->
        dispatch_async(dispatch_get_main_queue()) {
            val activity = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
            var presenter = UIApplication.sharedApplication.keyWindow?.rootViewController
            while (presenter?.presentedViewController != null) {
                presenter = presenter.presentedViewController
            }
            if (presenter == null) {
                done.resume(Handoff.Refused("no key window to present the share sheet from"))
            } else {
                presenter.presentViewController(activity, animated = true) { done.resume(Handoff.Accepted) }
            }
        }
    }
}
