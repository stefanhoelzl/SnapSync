package app.snapsync.ios.urlsession

import app.snapsync.logging.invocation
import app.snapsync.ports.BackgroundScheduler
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * The iOS 18–26.0 [BackgroundScheduler] backed by `BGTaskScheduler`. `scheduleNext()` (re)submits the
 * one-shot `BGProcessingTaskRequest` heartbeat — network required, external power NOT required, so the
 * OS grants windows often enough to drain a first whole-library upload and to catch new captures while
 * the app is closed. The actual `BGTaskScheduler.register(...)` handler wiring lives in the thin Swift
 * shell (it must run before app launch finishes); this only (re)submits and cancels the request.
 *
 * **Reachable only on a device.** On a simulator, even the app bundle whose `Info.plist` permits the identifier,
 * every submission is refused `BGTaskSchedulerErrorDomain/1` (`Unavailable`) and nothing is pending afterwards —
 * measured 2026-09-23, iOS 26.5 simulator, by running `BackgroundSchedulerContract` live in the simulator app. So the
 * contract is recorded on a device and replayed on every build (`BackgroundScheduler@IOS_DEVICE_APP.rec`), and no
 * simulator host binds it. ⏰ Re-measure at the next iOS major.
 */
@OptIn(ExperimentalForeignApi::class)
class IosBackgroundScheduler internal constructor(
    private val log: Logger,
    private val taskIdentifier: String,
    /**
     * Whether the wake needs the network. The upload heartbeat does; the download backstop does not — it
     * imports bytes that are already staged, and requiring a network would only defer it.
     */
    private val requiresNetwork: Boolean,
    // A small delay so a burst of re-arms coalesces into roughly one wake; the OS treats it as a
    // lower bound, scheduling opportunistically after it.
    private val earliestBeginSeconds: Double,
    // The operating-system boundary, so a recording taken on a device can replay against this code
    // (`BackgroundSchedulerContract`). Production always passes the real one.
    private val tasks: BackgroundTaskApi,
) : BackgroundScheduler {

    constructor(log: Logger, taskIdentifier: String, requiresNetwork: Boolean, earliestBeginSeconds: Double = 60.0) :
        this(log, taskIdentifier, requiresNetwork, earliestBeginSeconds, SystemBackgroundTaskApi)

    override fun scheduleNext(): Unit = log.invocation("scheduler.scheduleNext") {
        val request = BGProcessingTaskRequest(taskIdentifier)
        request.requiresNetworkConnectivity = requiresNetwork
        request.requiresExternalPower = false
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(earliestBeginSeconds)
        tasks.submit(request).onFailure { log.w(it) { "BGTask submit failed" } }
    }

    override fun cancel() = log.invocation("scheduler.cancel") {
        tasks.cancel(taskIdentifier)
    }
}
