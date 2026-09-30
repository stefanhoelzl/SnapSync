@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.background

import app.snapsync.logging.invocation
import app.snapsync.model.ScheduleResult
import app.snapsync.model.WakeCadence
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import app.snapsync.objc.ObjCFailure
import app.snapsync.objc.objcBoundary
import app.snapsync.ports.Completion
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeHandlers
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTask
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The iOS [Wake]: `BGTaskScheduler`. The heartbeat is one-shot and rides one of two task kinds by its cadence
 * (decision record `changes/timely-background-receiving`, D2):
 *
 * - **busy** — a `BGProcessingTaskRequest` on [HEARTBEAT_TASK_IDENTIFIER]: network as the trigger asks, external power
 *   NOT required, so the OS grants windows often enough to drain a first whole-library upload and to catch new
 *   captures while the app is closed;
 * - **idle** — a `BGAppRefreshTaskRequest` on [IDLE_TASK_IDENTIFIER]: iOS runs processing mostly when idle or
 *   charging, a refresh through the day. Refused — Background App Refresh off, Low Power Mode — it **falls back** to a
 *   processing request at the same earliest date: worse, never nothing.
 *
 * iOS keeps one pending request per identifier, and the two identifiers coexist — so **each submission cancels the
 * other identifier** first, or an idle re-arm would leave the busy wake standing. [cancel] withdraws both, and both
 * launch handlers route to [WakeId.Heartbeat].
 *
 * iOS gives an app no library-change wake (its equivalent is the upload extension, which the OS invokes on its own), so
 * [WakeId.LibraryChanged] is [ScheduleResult.Unsupported] and makes no operating-system call.
 *
 * **[listen] is the launch-handler registration** — one per identifier — Apple requires before the app finishes launching, once per process
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
        // One explicit registration per identifier: `RuntimeIdentityTest` reads each one's constant against the plist.
        registered(HEARTBEAT_TASK_IDENTIFIER, tasks.register(HEARTBEAT_TASK_IDENTIFIER) { launched(it) })
        registered(IDLE_TASK_IDENTIFIER, tasks.register(IDLE_TASK_IDENTIFIER) { launched(it) })
    }

    private fun launched(task: BGTask) = onTaskLaunched(task.identifier, TaskCompletion(task, log))

    /** Never silent: an unregistered task is a heartbeat the OS can never run, and nothing else would say so. */
    private fun registered(identifier: String, accepted: Boolean) {
        if (!accepted) log.e { "BGTask $identifier was not registered — is it in Info.plist?" }
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
            WakeId.Heartbeat -> TASK_IDENTIFIERS.forEach(tasks::cancel)
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
        val id = if (identifier in TASK_IDENTIFIERS) WakeId.Heartbeat else null
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
        val after = trigger as? WakeTrigger.After
        val earliestSeconds = after?.earliest?.inWholeMilliseconds?.div(MILLIS) ?: 0.0
        if (after?.cadence == WakeCadence.IDLE) {
            tasks.cancel(HEARTBEAT_TASK_IDENTIFIER)
            val refresh = BGAppRefreshTaskRequest(IDLE_TASK_IDENTIFIER)
            refresh.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(earliestSeconds)
            val refused = tasks.submit(refresh).exceptionOrNull() ?: return ScheduleResult.Scheduled
            // Background App Refresh is off, or Low Power Mode is on: a processing request still runs, if mostly on the
            // charger. Said out loud, because the idle wake is then much rarer.
            log.w(refused) { "the idle refresh was refused — falling back to a processing request" }
        } else {
            tasks.cancel(IDLE_TASK_IDENTIFIER)
        }
        return answerOf(tasks.submit(processing(after, earliestSeconds)))
    }

    private fun processing(after: WakeTrigger.After?, earliestSeconds: Double) =
        BGProcessingTaskRequest(HEARTBEAT_TASK_IDENTIFIER).apply {
            requiresNetworkConnectivity = after?.requiresNetwork ?: true
            requiresExternalPower = false
            earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(earliestSeconds)
        }

    private fun answerOf(submitted: Result<Unit>): ScheduleResult = submitted.fold(
        onSuccess = { ScheduleResult.Scheduled },
        onFailure = {
            log.w(it) { "BGTask submit failed" }
            val failure = it as? ObjCFailure
            ScheduleResult.Refused("${failure?.domain}/${failure?.code}")
        },
    )

    companion object {
        /** The heartbeat's `BGTaskSchedulerPermittedIdentifiers` entry, pinned against `Info.plist` by a guard. */
        const val HEARTBEAT_TASK_IDENTIFIER = "app.snapsync.upload.heartbeat"

        /** The idle heartbeat's `BGTaskSchedulerPermittedIdentifiers` entry, pinned against `Info.plist` by a guard. */
        const val IDLE_TASK_IDENTIFIER = "app.snapsync.heartbeat.idle"

        /** Every identifier the heartbeat rides, busy first. */
        val TASK_IDENTIFIERS = listOf(HEARTBEAT_TASK_IDENTIFIER, IDLE_TASK_IDENTIFIER)

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
