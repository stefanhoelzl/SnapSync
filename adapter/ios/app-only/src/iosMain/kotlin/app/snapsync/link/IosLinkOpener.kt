package app.snapsync.link

import app.snapsync.ports.LinkOpener
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The iOS [LinkOpener]: hands the URL to `UIApplication.openURL`, which leaves this app for whichever
 * app claims it — the App Store app for a store link. An app-only adapter, because `UIApplication` is
 * app-only API and an extension has nothing to leave from.
 *
 * A URL iOS cannot parse yields no `NSURL`, and that is simply not opened. There is nothing better to
 * do and nothing to report: the caller is the update-required screen, which is already showing the only
 * thing it has to say, and the port is fire-and-forget by contract. The value comes from the build's
 * own generated `Deployment.plist`, so an unparseable one is a build misconfiguration rather than a
 * state the product has.
 *
 * Marshalled onto the **main queue** for the reason `IosShareSheet` is: the container invokes this from
 * an Orbit intent on `Dispatchers.Default`, and `UIApplication` asserts the main queue. The adapter
 * names the lane itself so it is correct for any caller, not only the command declared on that lane.
 */
class IosLinkOpener : LinkOpener {

    override fun open(url: String) {
        val target = NSURL.URLWithString(url) ?: return
        dispatch_async(dispatch_get_main_queue()) {
            UIApplication.sharedApplication.openURL(target)
        }
    }
}
