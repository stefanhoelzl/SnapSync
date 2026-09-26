package app.snapsync.link

import app.snapsync.objc.objcBoundary
import co.touchlab.kermit.Logger
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * **The operating-system boundary of `IosSystemUi.openUrl`**: the one `UIApplication` call it makes (capability
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
 * The real call. Marshalled onto the **main queue** here, at the seam, for the reason `IosSystemUi.share` is:
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
