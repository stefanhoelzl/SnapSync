@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package app.snapsync.contract

import app.snapsync.background.BackgroundTaskApi
import app.snapsync.background.IosWake
import app.snapsync.background.LaunchedTask
import app.snapsync.background.SystemBackgroundTaskApi
import app.snapsync.config.IosBuildInfo
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Divergence
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.Recorder
import app.snapsync.contracts.Replayer
import app.snapsync.contracts.ScheduledWakes
import app.snapsync.contracts.WakeContract
import app.snapsync.contracts.WakeOs
import app.snapsync.contracts.WakeState
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.render
import app.snapsync.contracts.run
import app.snapsync.objc.ObjCFailure
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundRefreshStatus
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/*
 * `BGTaskScheduler`'s operating-system boundary as TEXT, and the entitled app's binding of `WakeContract` (recorded as
 * `BackgroundScheduler`)
 * (`docs/architecture.md`, "Hosts CI cannot reach are recorded at the operating-system boundary and replayed on
 * every build").
 *
 * Compiled into this module's `iosMain` only under `-Psnapsync.rig=true` (where the device records), and into `iosTest`
 * otherwise (where CI replays) — one file, so the recorder and the replayer cannot spell a call differently.
 */

/**
 * The heartbeat's identifier. A submission is accepted only for an identifier the app's own `Info.plist` lists under
 * `BGTaskSchedulerPermittedIdentifiers`, so the contract runs against production's own — which is why a device run
 * ends with the rig build's heartbeat cancelled (`CANCEL_EMPTY_IS_QUIET` runs last); the app's next trigger re-arms it.
 * The adapter's own constant, which `RuntimeIdentityTest` pins against the plist.
 */
internal const val HEARTBEAT = IosWake.HEARTBEAT_TASK_IDENTIFIER

/** Every identifier the heartbeat rides — busy and idle — each pinned against the plist the same way. */
private val HEARTBEATS = IosWake.TASK_IDENTIFIERS

/**
 * A request as text: its kind's attributes, the identifier, and — the earliest-begin date being absolute and moving
 * with every run — the date's presence, its value masked.
 */
private fun BGTaskRequest.render(): String {
    val begin = if (earliestBeginDate != null) "<masked>" else "none"
    return when (this) {
        is BGProcessingTaskRequest ->
            "submit(id=$identifier network=$requiresNetworkConnectivity power=$requiresExternalPower begin=$begin)"
        is BGAppRefreshTaskRequest -> "submitRefresh(id=$identifier begin=$begin)"
        else -> "submitOther(id=$identifier begin=$begin)"
    }
}

private const val ACCEPTED = "accepted"

/** A refusal is recorded as the system's domain and code — the whole of what the adapter reads from it. */
private fun Result<Unit>.renderAnswer(): String = exceptionOrNull()?.let {
    val failure = it as? ObjCFailure
    "refused domain=${failure?.domain} code=${failure?.code}"
} ?: ACCEPTED

private fun String.parseAnswer(identifier: String?): Result<Unit> {
    if (this == ACCEPTED) return Result.success(Unit)
    val domain = substringAfter("domain=").substringBefore(' ').takeIf { it != "null" }
    val code = substringAfter("code=").toLongOrNull()
    return Result.failure(ObjCFailure("submitTaskRequest($identifier)", domain, code, null))
}

private fun List<String>.renderIds() = sorted().joinToString(",", "[", "]")

private fun String.parseIds() = removePrefix("[").removeSuffix("]").split(',').filter { it.isNotEmpty() }

private fun registerCall(identifier: String) = "register(id=$identifier)"

private fun completeCall(identifier: String) = "complete(id=$identifier)"

private fun expiryCall(identifier: String) = "expirationHandler(id=$identifier)"

private fun launchedEvent(identifier: String) = "launched(id=$identifier)"

private fun expiredEvent(identifier: String) = "expired(id=$identifier)"

/**
 * The launches a device's contract run takes over from the app's own registration — iOS allows one per identifier per
 * process, and the app's composition made it at launch. While a clause holds an identifier, a task the system launches
 * under it reaches the clause's adapter; otherwise the app's.
 */
internal object LaunchTaps {
    private val claimed = AtomicReference<Map<String, (LaunchedTask) -> Unit>>(emptyMap())

    fun claim(identifier: String, launch: (LaunchedTask) -> Unit) {
        claimed.update { it + (identifier to launch) }
    }

    fun claimedFor(identifier: String): ((LaunchedTask) -> Unit)? = claimed.load()[identifier]

    fun releaseAll() = claimed.store(emptyMap())
}

/** [real], with each registration's launches handed to the clause that claims its identifier ([LaunchTaps]). */
internal class TappingBackgroundTaskApi(private val real: BackgroundTaskApi) : BackgroundTaskApi by real {
    override fun register(identifier: String, launch: (LaunchedTask) -> Unit): Boolean =
        real.register(identifier) { task -> (LaunchTaps.claimedFor(identifier) ?: launch)(task) }
}

/**
 * The app's own [IosWake] in a rig build: production's, over a task API whose launches a device's contract run can
 * take over for a clause. The rig's adapter set builds the root's wake with this ([rootWake] in the hook).
 */
fun contractTappableWake(log: Logger): IosWake = IosWake(log, TappingBackgroundTaskApi(SystemBackgroundTaskApi))

/** Passes [real]'s task through, recording its completion and expiration handler, and the expiry as an event. */
private class RecordingLaunchedTask(private val real: LaunchedTask, private val recorder: Recorder) : LaunchedTask {
    override val identifier: String get() = real.identifier

    override fun complete() {
        real.complete()
        recorder.record(completeCall(identifier), "done")
    }

    override fun onExpiry(expired: () -> Unit) {
        real.onExpiry {
            recorder.event(expiredEvent(identifier))
            expired()
        }
        recorder.record(expiryCall(identifier), "set")
    }
}

/**
 * Passes every call to [real] and records it, with the answer, in the clause block [recorder] has open. A registration
 * claims the app's launches of that identifier for the clause ([LaunchTaps]) — the real one was made at launch — and
 * each launch is recorded as an event, its task's calls as calls.
 */
internal class RecordingBackgroundTaskApi(
    private val real: BackgroundTaskApi,
    private val recorder: Recorder,
) : BackgroundTaskApi {
    override fun register(identifier: String, launch: (LaunchedTask) -> Unit): Boolean {
        LaunchTaps.claim(identifier) { task ->
            recorder.event(launchedEvent(identifier))
            launch(RecordingLaunchedTask(task, recorder))
        }
        recorder.record(registerCall(identifier), "true")
        return true
    }

    override fun submit(request: BGTaskRequest): Result<Unit> =
        real.submit(request).also { recorder.record(request.render(), it.renderAnswer()) }

    override fun cancel(identifier: String) =
        real.cancel(identifier).also { recorder.record("cancel(id=$identifier)", "done") }

    override suspend fun pendingIdentifiers(): List<String> =
        real.pendingIdentifiers().also { recorder.record("pending()", it.renderIds()) }
}

/**
 * Answers every call from one clause's recorded block, exactly and in order. Each recorded launch is delivered to the
 * registration of its identifier after the call it followed, on another thread, as the system does; a recorded expiry
 * waits for [endRunningWake], the moment the binding plays the system ending the task.
 */
internal class ReplayingBackgroundTaskApi(private val replayer: Replayer) : BackgroundTaskApi {
    private val launches = AtomicReference<Map<String, (LaunchedTask) -> Unit>>(emptyMap())
    private val expiryHandlers = AtomicReference<Map<String, () -> Unit>>(emptyMap())
    private val dueExpiries = AtomicReference<List<String>>(emptyList())

    override fun register(identifier: String, launch: (LaunchedTask) -> Unit): Boolean {
        val answer = replayer.answer(registerCall(identifier)).toBooleanStrict()
        launches.update { it + (identifier to launch) }
        deliverDue()
        return answer
    }

    override fun submit(request: BGTaskRequest): Result<Unit> =
        replayer.answer(request.render()).parseAnswer(request.identifier).also { deliverDue() }

    override fun cancel(identifier: String) {
        replayer.answer("cancel(id=$identifier)")
        deliverDue()
    }

    override suspend fun pendingIdentifiers(): List<String> = replayer.answer(
        "pending()",
    ).parseIds().also { deliverDue() }

    /** The system ends the running task: every recorded expiry now due reaches its task's handler. */
    suspend fun endRunningWake() {
        val deadline = TimeSource.Monotonic.markNow() + EXPIRY_WAIT
        while (dueExpiries.load().isEmpty()) {
            if (deadline.hasPassedNow()) {
                throw Divergence(
                    "the system ended the running wake, but the recording holds no expiry",
                )
            }
            delay(POLL_MILLIS)
        }
        dueExpiries.exchange(emptyList()).forEach { id ->
            val handler = expiryHandlers.load()[id] ?: throw Divergence("a recorded expiry names a task with no handler: $id")
            handler()
        }
    }

    private fun deliverDue() = replayer.takeEvents().forEach { event ->
        val launched = launches.load().entries.firstOrNull { launchedEvent(it.key) == event }
        val expired = launches.load().keys.firstOrNull { expiredEvent(it) == event }
        when {
            launched != null -> dispatch_async(
                dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u),
            ) {
                launched.value(ReplayedTask(launched.key))
            }
            expired != null -> dueExpiries.update { it + expired }
            else -> throw Divergence("a recorded event names no registered task: $event")
        }
    }

    private companion object {
        val EXPIRY_WAIT = 10.seconds
        const val POLL_MILLIS = 50L
    }

    private inner class ReplayedTask(override val identifier: String) : LaunchedTask {
        override fun complete() {
            replayer.answer(completeCall(identifier))
            deliverDue()
        }

        override fun onExpiry(expired: () -> Unit) {
            replayer.answer(expiryCall(identifier))
            expiryHandlers.update { it + (identifier to expired) }
            deliverDue()
        }
    }
}

/**
 * A fresh [IosWake] over [tasks], the system's queue EMPTY for the heartbeat on both its identifiers: entered by
 * cancels through the seam, so they are recorded and replayed like any other call. Shared by the device binding and its
 * replay, so both make identical calls. Disposal cancels again, leaving nothing pending; [afterDispose] runs last.
 */
internal fun schedulerInState(
    tasks: BackgroundTaskApi,
    log: CallLog,
    os: WakeOs? = null,
    afterDispose: () -> Unit = {},
): Entered<ScheduledWakes> {
    HEARTBEATS.forEach(tasks::cancel)
    val wake = IosWake(Logger.withTag("contract"), tasks).recorded(log)
    return Entered.Ready(
        ScheduledWakes(wake, os) { tasks.pendingIdentifiers().count { it in HEARTBEATS } },
        dispose = {
            HEARTBEATS.forEach(tasks::cancel)
            afterDispose()
        },
    )
}

/** iOS gives an app no library-change wake: nothing here can change the library into one. */
internal const val NO_LIBRARY_WAKE = "iOS gives an app no library-change wake"

/**
 * The real `BGTaskScheduler` in the entitled app, recording every call and iOS's answer. Delivering, the binding plays
 * the system's side as the documented debugging calls do: once a request is accepted the system is asked to launch its
 * task now, and to end it when a clause ends the running wake — neither recorded, since a replay is told of the launch
 * and the expiry themselves.
 */
internal class DeviceSchedulerBinding(private val recorder: Recorder) : Binding<WakeState, ScheduledWakes> {
    override val host = Host.IOS_DEVICE_APP
    override val kind = BindingKind.Live
    override val adapter = IosWake::class
    override val reaches = setOf(WakeState.EMPTY, WakeState.DELIVERING)

    override fun create(state: WakeState, clauseId: String, log: CallLog): Entered<ScheduledWakes> {
        // A state this host does not present: another platform's fact, or one no binding here enters.
        if (state !in reaches) return Entered.Unreachable("$state is not a state this host presents")
        recorder.open(clauseId)
        val recording = RecordingBackgroundTaskApi(SystemBackgroundTaskApi, recorder)
        if (state == WakeState.EMPTY) return schedulerInState(recording, log)
        val os = WakeOs(
            changeLibrary = { error(NO_LIBRARY_WAKE) },
            endRunningWake = { simulate(EXPIRATION, HEARTBEAT) },
        )
        return schedulerInState(LaunchingBackgroundTaskApi(recording), log, os, afterDispose = LaunchTaps::releaseAll)
    }
}

/** [real], asking the system to launch each task it accepts now — what a delivering clause waits for. */
private class LaunchingBackgroundTaskApi(private val real: BackgroundTaskApi) : BackgroundTaskApi by real {
    override fun submit(request: BGTaskRequest): Result<Unit> =
        real.submit(request).onSuccess { simulate(LAUNCH, request.identifier) }
}

private const val LAUNCH = "_simulateLaunchForTaskWithIdentifier:"
private const val EXPIRATION = "_simulateExpirationForTaskWithIdentifier:"

/** `BGTaskScheduler`'s debugging call [selector] for [identifier] — the calls Apple's lldb recipe makes. */
private fun simulate(selector: String, identifier: String) {
    BGTaskScheduler.sharedScheduler.performSelector(NSSelectorFromString(selector), withObject = identifier)
}

/**
 * Runs `WakeContract` against THIS app's `BGTaskScheduler` and renders the recording. Refuses on a simulator, whose
 * answers must never be filed under the device's name — and with Background App Refresh off, where iOS accepts every
 * request and keeps none (measured on the SE2 / iOS 26.6.2, 2026-09-30): a recording taken then fails every clause
 * that expects a pending wake, and no adapter could make it pass (decision record `changes/timely-background-receiving`,
 * D2).
 */
internal fun recordScheduler(): String {
    if (NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null) {
        return CONTRACT_REFUSED + "this process is a simulator app, not ${Host.IOS_DEVICE_APP}; record on a device.\n"
    }
    val refresh = UIApplication.sharedApplication.backgroundRefreshStatus
    if (refresh != UIBackgroundRefreshStatus.UIBackgroundRefreshStatusAvailable) {
        return CONTRACT_REFUSED +
            "Background App Refresh is not available (status $refresh): iOS keeps no background task then; turn it on " +
            "in Settings → General → Background App Refresh and record again.\n"
    }
    val recorder = Recorder()
    val results = run(WakeContract, DeviceSchedulerBinding(recorder))
    val env = IosBuildInfo()
    val header = listOf(
        "contract" to WakeContract.name,
        "host" to Host.IOS_DEVICE_APP.name,
        "device" to env.deviceModel,
        "os" to env.osVersion,
        "build" to env.buildNumber,
        "kotlin" to KotlinVersion.CURRENT.toString(),
        "recorded" to NSISO8601DateFormatter().stringFromDate(NSDate()),
    ) + results.map { "live ${it.clauseId}" to it.outcome.render() }
    return recorder.recording(header).render()
}
