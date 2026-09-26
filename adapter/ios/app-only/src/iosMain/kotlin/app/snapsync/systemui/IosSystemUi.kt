package app.snapsync.systemui

import app.snapsync.link.SystemUrlOpenerApi
import app.snapsync.link.UrlOpenerApi
import app.snapsync.model.Handoff
import app.snapsync.objc.objcBoundary
import app.snapsync.ports.SystemUi
import co.touchlab.kermit.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The iOS [SystemUi]: the system share sheet, `UIApplication.openURL`, and this app's Settings page — an app-only
 * adapter, because presenting UI and `UIApplication` are app-only API and an extension has nothing to present over or
 * leave from.
 *
 * Every member names the **main queue** itself: the container invokes these commands from an Orbit intent on
 * `Dispatchers.Default`, and UIKit asserts the main queue (`dispatch_assert_queue`) — presenting off-main traps with
 * SIGTRAP. Naming the lane here keeps the adapter correct for any caller, not only the commands declared on that
 * lane (law "Dispatcher lanes are fixed by the composition").
 */
class IosSystemUi internal constructor(private val urls: UrlOpenerApi) : SystemUi {

    constructor() : this(SystemUrlOpenerApi)

    private val shareLog = Logger.withTag("shareSheet")
    private val linkLog = Logger.withTag("linkOpener")
    private val settingsLog = Logger.withTag("photoPermission")

    /**
     * Presents `UIActivityViewController` carrying [text], from the current top-most view controller. iPhone-only /
     * portrait, so no popover source is needed. [Handoff.Accepted] once UIKit reports the sheet presented;
     * [Handoff.Refused] when there is no key window to present from — the tap then does nothing on screen.
     *
     * The presenter walk (following `presentedViewController` to the top of the presentation stack) is technology
     * mechanics: UIKit rejects presentation from a covered controller.
     */
    override suspend fun share(text: String): Handoff = suspendCoroutine { done ->
        dispatch_async(dispatch_get_main_queue()) { objcBoundary(shareLog, "share") {
            val activity = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
            var presenter = UIApplication.sharedApplication.keyWindow?.rootViewController
            while (presenter?.presentedViewController != null) {
                presenter = presenter.presentedViewController
            }
            if (presenter == null) {
                done.resume(Handoff.Refused("no key window to present the share sheet from"))
            } else {
                presenter.presentViewController(activity, animated = true) {
                    objcBoundary(shareLog, "share.completion") { done.resume(Handoff.Accepted) }
                }
            }
        } }
    }

    /**
     * Hands [url] to `UIApplication.openURL(_:options:completionHandler:)`, which leaves this app for whichever app
     * claims it — the App Store app for a store link — and answers what iOS answered.
     *
     * A URL iOS cannot parse is [Handoff.Refused] without asking iOS anything. The value comes from the build's own
     * generated `Deployment.plist`, so that is a build misconfiguration rather than a state the product has — and it
     * is answered, not swallowed, so the caller records it.
     *
     * The one-argument `openURL(_:)` must not come back. Measured on the SE2 (iOS 26.6, 2026-09-23) with the build's
     * own store URL: it answered `false` and opened nothing, while this form opened the App Store and completed with
     * `true`. `LinkOpenerContract`'s device recording pins the call.
     */
    override suspend fun openUrl(url: String): Handoff {
        val target = NSURL.URLWithString(url)
            ?: return Handoff.Refused("'$url' is not a URL iOS can parse — a build misconfiguration")
        // The completion is what Objective-C calls (through SystemUrlOpenerApi): contained, like every such block.
        val opened = suspendCoroutine { done ->
            urls.open(target) { accepted -> objcBoundary(linkLog, "openLink.completion") { done.resume(accepted) } }
        }
        return if (opened) Handoff.Accepted else Handoff.Refused("iOS did not open $url")
    }

    /** Opens this app's page in the Settings app — the `DENIED` affordance (capability `photo-access`). */
    override fun openSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        dispatch_async(dispatch_get_main_queue()) {
            objcBoundary(settingsLog, "openSettings") {
                UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
            }
        }
    }
}
