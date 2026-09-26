@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.contract

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.Recorder
import app.snapsync.contracts.Replayer
import app.snapsync.contracts.ScheduledWakes
import app.snapsync.contracts.WakeContract
import app.snapsync.contracts.WakeState
import app.snapsync.contracts.render
import app.snapsync.contracts.run
import app.snapsync.background.BackgroundTaskApi
import app.snapsync.background.IosWake
import app.snapsync.background.SystemBackgroundTaskApi
import app.snapsync.logging.deviceDiagnosticEnvironment
import app.snapsync.objc.ObjCFailure
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTask
import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSProcessInfo

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
    // No clause registers (a second registration in one process raises), so a recording holds none.
    override fun register(identifier: String, launch: (BGTask) -> Unit): Boolean =
        error("a contract clause registers no launch handler — the app's composition already did")

    override fun submit(request: BGProcessingTaskRequest): Result<Unit> =
        real.submit(request).also { recorder.record(request.render(), it.renderAnswer()) }

    override fun cancel(identifier: String) =
        real.cancel(identifier).also { recorder.record("cancel(id=$identifier)", "done") }

    override suspend fun pendingIdentifiers(): List<String> =
        real.pendingIdentifiers().also { recorder.record("pending()", it.renderIds()) }
}

/** Answers every call from one clause's recorded block, exactly and in order. */
internal class ReplayingBackgroundTaskApi(private val replayer: Replayer) : BackgroundTaskApi {
    override fun register(identifier: String, launch: (BGTask) -> Unit): Boolean =
        error("a contract clause registers no launch handler, so a recording holds none to replay")

    override fun submit(request: BGProcessingTaskRequest): Result<Unit> =
        replayer.answer(request.render()).parseAnswer(request.identifier)

    override fun cancel(identifier: String) {
        replayer.answer("cancel(id=$identifier)")
    }

    override suspend fun pendingIdentifiers(): List<String> = replayer.answer("pending()").parseIds()
}

/**
 * A fresh [IosWake] over [tasks], the system's queue EMPTY for the heartbeat: entered by a cancel through the seam, so
 * it is recorded and replayed like any other call. Shared by the device binding and its replay, so both make identical
 * calls. Disposal cancels again, leaving nothing pending; [afterDispose] runs last.
 */
internal fun schedulerInState(tasks: BackgroundTaskApi, afterDispose: () -> Unit = {}): Entered<ScheduledWakes> {
    tasks.cancel(HEARTBEAT)
    val wake = IosWake(Logger.withTag("contract"), tasks)
    return Entered.Ready(
        ScheduledWakes(wake) { tasks.pendingIdentifiers().count { it == HEARTBEAT } },
        dispose = {
            tasks.cancel(HEARTBEAT)
            afterDispose()
        },
    )
}

/** The real `BGTaskScheduler` in the entitled app, recording every call and iOS's answer. */
internal class DeviceSchedulerBinding(private val recorder: Recorder) : Binding<WakeState, ScheduledWakes> {
    override val host = Host.IOS_DEVICE_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(WakeState.EMPTY)

    override fun create(state: WakeState, clauseId: String): Entered<ScheduledWakes> {
        recorder.open(clauseId)
        return schedulerInState(RecordingBackgroundTaskApi(SystemBackgroundTaskApi, recorder))
    }
}

/**
 * Runs `WakeContract` against THIS app's `BGTaskScheduler` and renders the recording. Refuses on a
 * simulator, whose answers must never be filed under the device's name.
 */
internal fun recordScheduler(): String {
    if (NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null) {
        return CONTRACT_REFUSED + "this process is a simulator app, not ${Host.IOS_DEVICE_APP}; record on a device.\n"
    }
    val recorder = Recorder()
    val results = run(WakeContract, DeviceSchedulerBinding(recorder))
    val env = deviceDiagnosticEnvironment(uploadTier = "n/a")
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
