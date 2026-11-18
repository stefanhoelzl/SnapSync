package app.snapsync.link

import app.snapsync.inbound.Log
import app.snapsync.objc.onQueue
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIScene
import platform.darwin.dispatch_get_main_queue

/**
 * **The operating-system boundary of `IosSystemUi.openUrl`**: the `UIApplication` calls it makes (capability
 * `docs/architecture.md`, "Hosts CI cannot reach are recorded at the operating-system boundary and replayed on
 * every build"). Opening a URL another app claims backgrounds this app, so no CI host can run it live; it
 * is recorded on a device and replayed through this seam. `internal`: the recording and replaying
 * implementations live in this module's rig-gated source set.
 */
internal interface UrlOpenerApi {
    /** Ask iOS to open [url]; [completion] receives iOS's answer. */
    fun open(url: NSURL, completion: (Boolean) -> Unit)

    /**
     * What iOS says of its own state, read after it refused to open [url] — it gives no reason of its own. Asked only
     * after a refusal, so an open that worked costs nothing more.
     */
    fun afterRefusal(url: NSURL, completion: (RefusalFacts) -> Unit)
}

/**
 * iOS's raw answers after a refused open, as the OS gave them, so a recording holds the platform's values and the
 * adapter's reading of them runs on every replay:
 * - [applicationState]: `UIApplicationState`'s value (0 active, 1 inactive, 2 background);
 * - [canOpen]: `canOpenURL` for the refused URL;
 * - [sceneStates]: each connected scene's `UISceneActivationState` (-1 unattached, 0 foreground active,
 *   1 foreground inactive, 2 background).
 */
internal data class RefusalFacts(val applicationState: Long, val canOpen: Boolean, val sceneStates: List<Long>)

/**
 * The real calls. Marshalled onto the **main queue** here, at the seam, for the reason `IosSystemUi.share` is:
 * the container invokes the port from an Orbit intent on `Dispatchers.Default`, and `UIApplication`
 * asserts the main queue. Naming the lane at the seam keeps the adapter correct for any caller, and keeps
 * a replay — which answers synchronously — off a main queue a test executable never services.
 */
internal object SystemUrlOpenerApi : UrlOpenerApi {
    private val log = Log("linkOpener")

    override fun open(url: NSURL, completion: (Boolean) -> Unit) {
        onQueue(dispatch_get_main_queue(), log, "openLink") {
            UIApplication.sharedApplication.openURL(
                url,
                options = emptyMap<Any?, Any>(),
                completionHandler = completion,
            )
        }
    }

    override fun afterRefusal(url: NSURL, completion: (RefusalFacts) -> Unit) {
        onQueue(dispatch_get_main_queue(), log, "openLink.afterRefusal") {
            val application = UIApplication.sharedApplication
            completion(
                RefusalFacts(
                    applicationState = application.applicationState.value,
                    canOpen = application.canOpenURL(url),
                    sceneStates = application.connectedScenes.filterIsInstance<UIScene>().map { it.activationState },
                ),
            )
        }
    }
}
