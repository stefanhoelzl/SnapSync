package app.snapsync.link

import app.snapsync.objc.objcBoundary
import app.snapsync.model.Handoff
import app.snapsync.ports.LinkOpener
import co.touchlab.kermit.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The iOS [LinkOpener]: hands the URL to `UIApplication.openURL(_:options:completionHandler:)`, which
 * leaves this app for whichever app claims it — the App Store app for a store link — and answers what
 * iOS answered. An app-only adapter, because `UIApplication` is app-only API and an extension has nothing
 * to leave from.
 *
 * A URL iOS cannot parse is [Handoff.Refused] without asking iOS anything. The value comes from the
 * build's own generated `Deployment.plist`, so that is a build misconfiguration rather than a state the
 * product has — and it is answered, not swallowed, so the caller records it.
 *
 * The one-argument `openURL(_:)` must not come back. Measured on the SE2 (iOS 26.6, 2026-09-23) with the
 * build's own store URL: it answered `false` and opened nothing, while this form opened the App Store and
 * completed with `true`. It was the form here until then, with its answer discarded — so the store
 * button did nothing and nothing recorded it. `LinkOpenerContract`'s device recording pins the call.
 */
class IosLinkOpener internal constructor(private val platform: UrlOpenerApi) : LinkOpener {

    constructor() : this(SystemUrlOpenerApi)

    private val log = Logger.withTag("linkOpener")

    override suspend fun open(url: String): Handoff {
        val target = NSURL.URLWithString(url)
            ?: return Handoff.Refused("'$url' is not a URL iOS can parse — a build misconfiguration")
        // The completion is what Objective-C calls (through SystemUrlOpenerApi): contained, like every such block.
        val opened = suspendCoroutine { done ->
            platform.open(target) { accepted -> objcBoundary(log, "openLink.completion") { done.resume(accepted) } }
        }
        return if (opened) Handoff.Accepted else Handoff.Refused("iOS did not open $url")
    }
}

/**
 * **The operating-system boundary of [IosLinkOpener]**: the one `UIApplication` call it makes (capability
 * `docs/architecture.md`, "Hosts CI cannot reach are recorded at the operating-system boundary and replayed on
 * every build"). Opening a URL another app claims backgrounds this app, so no CI host can run it live; it
 * is recorded on a device and replayed through this seam. `internal`: the recording and replaying
 * implementations live in this module's rig-gated source set.
 */
internal fun interface UrlOpenerApi {
    /** Ask iOS to open [url]; [completion] receives iOS's answer. */
    fun open(url: NSURL, completion: (Boolean) -> Unit)
}

/**
 * The real call. Marshalled onto the **main queue** here, at the seam, for the reason `IosShareSheet` is:
 * the container invokes the port from an Orbit intent on `Dispatchers.Default`, and `UIApplication`
 * asserts the main queue. Naming the lane at the seam keeps the adapter correct for any caller, and keeps
 * a replay — which answers synchronously — off a main queue a test executable never services.
 */
internal object SystemUrlOpenerApi : UrlOpenerApi {
    override fun open(url: NSURL, completion: (Boolean) -> Unit) {
        dispatch_async(dispatch_get_main_queue()) {
            objcBoundary(Logger.withTag("linkOpener"), "openLink") {
                UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = completion)
            }
        }
    }
}
