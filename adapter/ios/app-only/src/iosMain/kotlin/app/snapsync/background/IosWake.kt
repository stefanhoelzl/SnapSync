@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.background

import app.snapsync.logging.invocation
import app.snapsync.model.ScheduleResult
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import app.snapsync.objc.ObjCFailure
import app.snapsync.objc.objcBoundary
import app.snapsync.ports.Completion
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeHandlers
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTask
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The iOS [Wake]: `BGTaskScheduler`. The heartbeat is a one-shot `BGProcessingTaskRequest` — network as the trigger
 * asks, external power NOT required, so the OS grants windows often enough to drain a first whole-library upload and
 * to catch new captures while the app is closed. iOS gives an app no library-change wake (its equivalent is the upload
 * extension, which the OS invokes on its own), so [WakeId.LibraryChanged] is [ScheduleResult.Unsupported] and makes no
 * operating-system call.
 *
 * **[listen] is the launch-handler registration** Apple requires before the app finishes launching, once per process
 * (a second registration raises): the root composes its graph from `onLaunch`, so the host zone's one `listen` lands
 * in time. It moved here from the Swift shell in phase 11f, so the launch handler, the task's completion and its
 * expiration handler are one adapter's, and the shell only forces the root.
 *
 * **Reachable only on a device.** On a simulator every submission is refused `BGTaskSchedulerErrorDomain/1`
 * (`Unavailable`) and nothing is pending afterwards — measured 2026-09-23, iOS 26.5 simulator. So the contract is
 * recorded on a device and replayed on every build (`BackgroundScheduler@IOS_DEVICE_APP.rec`, its recorded name), and
 * no simulator host binds it. ⏰ Re-measure at the next iOS major.
 */
@OptIn(ExperimentalForeignApi::class)
class IosWake internal constructor(
    private val log: Logger,
    // The operating-system boundary, so a recording taken on a device can replay against this code (`WakeContract`).
    // Production always passes the real one.
    private val tasks: BackgroundTaskApi,
) : Wake {

    constructor(log: Logger) : this(log, SystemBackgroundTaskApi)

    private val handlers = AtomicReference<WakeHandlers?>(null)

    override fun listen(handlers: WakeHandlers) = log.invocation("wake.listen") {
        this.handlers.store(handlers)
        val registered = tasks.register(HEARTBEAT_TASK_IDENTIFIER) { task ->
            onTaskLaunched(task.identifier, TaskCompletion(task, log))
        }
        // Never silent: an unregistered task is a heartbeat the OS can never run, and nothing else would say so.
        if (!registered) log.e { "BGTask $HEARTBEAT_TASK_IDENTIFIER was not registered — is it in Info.plist?" }
    }

    override fun schedule(id: WakeId, trigger: WakeTrigger): ScheduleResult =
        log.invocation("wake.schedule", params = "id=$id") {
            when (id) {
                WakeId.Heartbeat -> submit(trigger)
                WakeId.LibraryChanged -> ScheduleResult.Unsupported
            }
        }

    override fun cancel(id: WakeId) = log.invocation("wake.cancel", params = "id=$id") {
        when (id) {
            WakeId.Heartbeat -> tasks.cancel(HEARTBEAT_TASK_IDENTIFIER)
            WakeId.LibraryChanged -> Unit
        }
    }

    /**
     * The operating system launched the background task [identifier] — its own identifier, never one the shell chose —
     * and hands [completion]. Routed to the registered handlers by the identifier; one this adapter does not know is
     * completed at once and logged, because a task held forever costs the app its future background time.
     *
     * Public for the control channel, which plays the operating system (`/os onBackgroundTask`) until the entry surface
     * becomes event ports (11g).
     */
    fun onTaskLaunched(identifier: String, completion: Completion) =
        log.invocation("wake.onTaskLaunched", params = "identifier=$identifier") { route(identifier, completion) }

    private fun route(identifier: String, completion: Completion) {
        val id = if (identifier == HEARTBEAT_TASK_IDENTIFIER) WakeId.Heartbeat else null
        val registered = handlers.load()
        when {
            id == null -> {
                log.w { "unknown background task '$identifier' — completed without work" }
                completion.complete()
            }
            registered == null -> {
                log.e { "background task '$identifier' launched before the composition listened — completed at once" }
                completion.complete()
            }
            else -> registered.onWake(id, completion)
        }
    }

    private fun submit(trigger: WakeTrigger): ScheduleResult {
        val request = BGProcessingTaskRequest(HEARTBEAT_TASK_IDENTIFIER)
        val after = trigger as? WakeTrigger.After
        request.requiresNetworkConnectivity = after?.requiresNetwork ?: true
        request.requiresExternalPower = false
        val earliestSeconds = after?.earliest?.inWholeMilliseconds?.div(MILLIS) ?: 0.0
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(earliestSeconds)
        return tasks.submit(request).fold(
            onSuccess = { ScheduleResult.Scheduled },
            onFailure = {
                log.w(it) { "BGTask submit failed" }
                val failure = it as? ObjCFailure
                ScheduleResult.Refused("${failure?.domain}/${failure?.code}")
            },
        )
    }

    companion object {
        /** The heartbeat's `BGTaskSchedulerPermittedIdentifiers` entry, pinned against `Info.plist` by a guard. */
        const val HEARTBEAT_TASK_IDENTIFIER = "app.snapsync.upload.heartbeat"

        private const val MILLIS = 1000.0
    }
}

/**
 * A `BGTask`'s completion (`setTaskCompleted`) and expiration handler as one [Completion]: completed once, and its
 * expiry delivered once to the action the core registers — at once if the expiry came first.
 */
@OptIn(ExperimentalForeignApi::class)
private class TaskCompletion(private val task: BGTask, private val log: Logger) : Completion {
    private val completed = AtomicBoolean(false)
    private val expired = AtomicBoolean(false)
    private val actionRan = AtomicBoolean(false)
    private val action = AtomicReference<(() -> Unit)?>(null)

    init {
        task.expirationHandler = {
            objcBoundary(log, "bgTask.expirationHandler(${task.identifier})") {
                expired.store(true)
                action.load()?.let(::runOnce)
            }
        }
    }

    override fun complete() {
        if (completed.compareAndSet(expectedValue = false, newValue = true)) task.setTaskCompletedWithSuccess(true)
    }

    override fun onExpired(action: () -> Unit) {
        this.action.store(action)
        if (expired.load()) runOnce(action)
    }

    private fun runOnce(action: () -> Unit) {
        if (actionRan.compareAndSet(expectedValue = false, newValue = true)) action()
    }
}
