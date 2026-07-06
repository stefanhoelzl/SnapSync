package app.snapsync.ios.urlsession

import app.snapsync.upload.BackgroundScheduler
import app.snapsync.logging.invocation
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * The iOS 18–26.0 [BackgroundScheduler] backed by `BGTaskScheduler`. `scheduleNext()` (re)submits the
 * one-shot `BGProcessingTaskRequest` heartbeat — network required, external power NOT required, so the
 * OS grants windows often enough to drain a first whole-library backup and to catch new captures while
 * the app is closed. The actual `BGTaskScheduler.register(...)` handler wiring lives in the thin Swift
 * shell (it must run before app launch finishes); this only (re)submits and cancels the request.
 */
@OptIn(ExperimentalForeignApi::class)
class IosBackgroundScheduler(
    private val log: Logger,
    private val taskIdentifier: String,
    // A small delay so a burst of re-arms coalesces into roughly one wake; the OS treats it as a
    // lower bound, scheduling opportunistically after it.
    private val earliestBeginSeconds: Double = 60.0,
) : BackgroundScheduler {

    override fun scheduleNext() = log.invocation("scheduler.scheduleNext") {
        val request = BGProcessingTaskRequest(taskIdentifier)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(earliestBeginSeconds)
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            val ok = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, err.ptr)
            if (!ok) log.w { "BGTask submit failed: ${err.value?.localizedDescription}" }
        }
    }

    override fun cancel() = log.invocation("scheduler.cancel") {
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(taskIdentifier)
    }
}
