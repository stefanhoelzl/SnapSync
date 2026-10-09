package app.snapsync.systemui

import app.snapsync.link.SystemUrlOpenerApi
import app.snapsync.link.UrlOpenerApi
import app.snapsync.model.Handoff
import app.snapsync.objc.objcBoundary
import app.snapsync.objc.objcCallback
import app.snapsync.objc.onQueue
import app.snapsync.ports.SystemUi
import co.touchlab.kermit.Logger
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIApplicationState
import platform.darwin.dispatch_get_main_queue

/**
 * The operating-system boundary of [IosSystemUi.share]: presenting the system share sheet over the key window's top-most
 * controller, and nothing else — so the answer with no window to present from, recorded on a phone launched in the
 * background, replays on every build.
 */
internal fun interface ShareSheetApi {
    /**
     * Present the sheet carrying [text] titled [title]; [answered] receives `true` once it is shown, `false` when there is
     * nothing on screen to present from — the app not in front, or no key window.
     */
    fun present(text: String, title: String, answered: (Boolean) -> Unit)
}

/**
 * The real one — on the **main queue**, named here at the seam for the reason [SystemUrlOpenerApi] names it: UIKit
 * asserts it, the port is invoked from `Dispatchers.Default`, and a replay answers off a main queue a test executable
 * never services. The presenter walk (following `presentedViewController` to the top of the presentation stack) is
 * technology mechanics: UIKit rejects presentation from a covered controller.
 */
internal object SystemShareSheetApi : ShareSheetApi {
    private val log = Logger.withTag("shareSheet")

    override fun present(text: String, title: String, answered: (Boolean) -> Unit) {
        onQueue(dispatch_get_main_queue(), log, "share") {
            val activity = UIActivityViewController(
                activityItems = listOf(InviteActivityItem(text, title)),
                applicationActivities = null,
            )
            val application = UIApplication.sharedApplication
            var presenter = application.keyWindow?.rootViewController
            while (presenter?.presentedViewController != null) {
                presenter = presenter.presentedViewController
            }
            // In front, or nobody sees it: a launch the system made in the background has a key window too (measured,
            // SE2 / iOS 26.6.2), and UIKit presents over it and reports the sheet shown.
            if (application.applicationState != UIApplicationState.UIApplicationStateActive || presenter == null) {
                answered(false)
            } else {
                presenter.presentViewController(activity, animated = true) {
                    objcBoundary(log, "share.presented") { answered(true) }
                }
            }
        }
    }
}

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
class IosSystemUi internal constructor(
    private val urls: UrlOpenerApi,
    private val sheets: ShareSheetApi = SystemShareSheetApi,
) : SystemUi {

    constructor() : this(SystemUrlOpenerApi)

    private val shareLog = Logger.withTag("shareSheet")
    private val linkLog = Logger.withTag("linkOpener")
    private val settingsLog = Logger.withTag("photoPermission")

    /**
     * Presents `UIActivityViewController` carrying [text] — as a URL titled [title] ([InviteActivityItem]) — from the
     * current top-most view controller. iPhone-only /
     * portrait, so no popover source is needed. [Handoff.Accepted] once UIKit reports the sheet presented;
     * [Handoff.Refused] when there is nothing on screen to present from — the app not in front, or no key window. The
     * presentation is [ShareSheetApi]'s.
     */
    override suspend fun share(
        text: String,
        title: String,
    ): Handoff = objcCallback(shareLog, "share.completion") { done ->
        sheets.present(text, title) { shown ->
            objcBoundary(done) {
                if (shown) Handoff.Accepted else Handoff.Refused("nothing on screen to present the share sheet from")
            }
        }
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
        val opened = objcCallback(linkLog, "openLink.completion") { done ->
            urls.open(target) { accepted -> objcBoundary(done) { accepted } }
        }
        return if (opened) Handoff.Accepted else Handoff.Refused("iOS did not open $url")
    }

    /** Opens this app's page in the Settings app — the `DENIED` affordance. */
    override fun openSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        onQueue(dispatch_get_main_queue(), settingsLog, "openSettings") {
            UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
        }
    }
}
