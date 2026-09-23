@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.contract

import app.snapsync.contracts.BackgroundSchedulerContract
import app.snapsync.contracts.BackgroundSchedulerState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.Recorder
import app.snapsync.contracts.Replayer
import app.snapsync.contracts.ScheduledWakes
import app.snapsync.contracts.render
import app.snapsync.contracts.run
import app.snapsync.ios.urlsession.BackgroundTaskApi
import app.snapsync.ios.urlsession.IosBackgroundScheduler
import app.snapsync.ios.urlsession.SystemBackgroundTaskApi
import app.snapsync.logging.deviceDiagnosticEnvironment
import app.snapsync.objc.ObjCFailure
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSProcessInfo

/*
 * `BGTaskScheduler`'s operating-system boundary as TEXT, and the entitled app's binding of `BackgroundSchedulerContract`
 * (capability `port-contracts`, "Hosts CI cannot reach are recorded at the operating-system boundary and replayed on
 * every build").
 *
 * Compiled into this module's `iosMain` only under `-Psnapsync.rig=true` (where the device records), and into `iosTest`
 * otherwise (where CI replays) — one file, so the recorder and the replayer cannot spell a call differently.
 */

/**
 * The heartbeat's identifier. A submission is accepted only for an identifier the app's own `Info.plist` lists under
 * `BGTaskSchedulerPermittedIdentifiers`, so the contract runs against production's own — which is why a device run
 * ends with the rig build's heartbeat cancelled (`CANCEL_EMPTY_IS_QUIET` runs last); the app's next trigger re-arms it.
 * A copy of `UrlSessionUploadController.HEARTBEAT_TASK_IDENTIFIER` (in `:app:ios`, which this module cannot see), whose
 * production copy `RuntimeIdentityTest` pins against the plist; were this one to drift, every submit in the recording
 * would read as refused and `SCHEDULE_ARMS_ONE` would fail on the device.
 */
internal const val HEARTBEAT = "app.snapsync.upload.heartbeat"

/** The earliest-begin date is absolute and moves with every run; its presence is recorded, its value masked. */
private fun BGProcessingTaskRequest.render() =
    "submit(id=$identifier network=$requiresNetworkConnectivity power=$requiresExternalPower " +
        "begin=${if (earliestBeginDate != null) "<masked>" else "none"})"

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

/** Passes every call to [real] and records it, with the answer, in the clause block [recorder] has open. */
internal class RecordingBackgroundTaskApi(private val real: BackgroundTaskApi, private val recorder: Recorder) : BackgroundTaskApi {
    override fun submit(request: BGProcessingTaskRequest): Result<Unit> =
        real.submit(request).also { recorder.record(request.render(), it.renderAnswer()) }

    override fun cancel(identifier: String) =
        real.cancel(identifier).also { recorder.record("cancel(id=$identifier)", "done") }

    override suspend fun pendingIdentifiers(): List<String> =
        real.pendingIdentifiers().also { recorder.record("pending()", it.renderIds()) }
}

/** Answers every call from one clause's recorded block, exactly and in order. */
internal class ReplayingBackgroundTaskApi(private val replayer: Replayer) : BackgroundTaskApi {
    override fun submit(request: BGProcessingTaskRequest): Result<Unit> =
        replayer.answer(request.render()).parseAnswer(request.identifier)

    override fun cancel(identifier: String) {
        replayer.answer("cancel(id=$identifier)")
    }

    override suspend fun pendingIdentifiers(): List<String> = replayer.answer("pending()").parseIds()
}

/**
 * A fresh [IosBackgroundScheduler] over [tasks], the system's queue EMPTY for the heartbeat: entered by a cancel
 * through the seam, so it is recorded and replayed like any other call. Shared by the device binding and its replay,
 * so both make identical calls. Disposal cancels again, leaving nothing pending; [afterDispose] runs last.
 */
internal fun schedulerInState(tasks: BackgroundTaskApi, afterDispose: () -> Unit = {}): Entered<ScheduledWakes> {
    tasks.cancel(HEARTBEAT)
    val scheduler = IosBackgroundScheduler(Logger.withTag("contract"), HEARTBEAT, true, 60.0, tasks)
    return Entered.Ready(
        ScheduledWakes(scheduler) { tasks.pendingIdentifiers().count { it == HEARTBEAT } },
        dispose = {
            tasks.cancel(HEARTBEAT)
            afterDispose()
        },
    )
}

/** The real `BGTaskScheduler` in the entitled app, recording every call and iOS's answer. */
internal class DeviceSchedulerBinding(private val recorder: Recorder) : Binding<BackgroundSchedulerState, ScheduledWakes> {
    override val host = Host.IOS_DEVICE_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(BackgroundSchedulerState.EMPTY)

    override fun create(state: BackgroundSchedulerState, clauseId: String): Entered<ScheduledWakes> {
        recorder.open(clauseId)
        return schedulerInState(RecordingBackgroundTaskApi(SystemBackgroundTaskApi, recorder))
    }
}

/**
 * Runs `BackgroundSchedulerContract` against THIS app's `BGTaskScheduler` and renders the recording. Refuses on a
 * simulator, whose answers must never be filed under the device's name.
 */
internal fun recordScheduler(): String {
    if (NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null) {
        return CONTRACT_REFUSED + "this process is a simulator app, not ${Host.IOS_DEVICE_APP}; record on a device.\n"
    }
    val recorder = Recorder()
    val results = run(BackgroundSchedulerContract, DeviceSchedulerBinding(recorder))
    val env = deviceDiagnosticEnvironment(uploadTier = "n/a")
    val header = listOf(
        "contract" to BackgroundSchedulerContract.name,
        "host" to Host.IOS_DEVICE_APP.name,
        "device" to env.deviceModel,
        "os" to env.osVersion,
        "build" to env.buildNumber,
        "kotlin" to KotlinVersion.CURRENT.toString(),
        "recorded" to NSISO8601DateFormatter().stringFromDate(NSDate()),
    ) + results.map { "live ${it.clauseId}" to it.outcome.render() }
    return recorder.recording(header).render()
}
