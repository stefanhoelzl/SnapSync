@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.contract

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Divergence
import app.snapsync.contracts.DownloadContract
import app.snapsync.contracts.DownloadState
import app.snapsync.contracts.DownloadUnderTest
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.InAppContract
import app.snapsync.contracts.Recorder
import app.snapsync.contracts.Replayer
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.recordingName
import app.snapsync.contracts.render
import app.snapsync.contracts.run
import app.snapsync.download.DownloadSession
import app.snapsync.download.DownloadSessionApi
import app.snapsync.download.DownloadSessionEvents
import app.snapsync.download.DownloadTask
import app.snapsync.download.IosDownload
import app.snapsync.download.SystemDownloadSessionApi
import app.snapsync.ios.urlsession.BackgroundSessions
import app.snapsync.ios.urlsession.transferSessionConfiguration
import app.snapsync.logging.deviceDiagnosticEnvironment
import app.snapsync.model.TransferOutcome
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionTask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_SEC
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.posix.exit

/*
 * The download RELAUNCH run: what the operating system delivers to an app it launched in the background for the
 * download session's events, recorded across the two processes it takes (`docs/testing.md`, "Record and replay") as
 * the upload relaunch is (`RelaunchContracts.kt`), and replayed on every build.
 *
 * Compiled into this module's `iosMain` only under `-Psnapsync.rig=true` (where the device records), and into `iosTest`
 * otherwise (where CI replays) — one file, so the recorder and the replayer cannot spell a call differently.
 */

/** The session the run prepares its transfer in, apart from production's: claimed in a rig build. */
internal const val DOWNLOAD_CONTRACT_SESSION = "app.snapsync.contract.download.relaunch"

/** The one clause the relaunch records, alone in its run. */
private const val DOWNLOAD_RELAUNCH_CLAUSE = "RELAUNCHED_EVENTS_ARE_HANDED_OVER_THEN_DRAINED"

/**
 * Where the prepared transfer fetches from: a path of this project's own domain that nothing serves, so it ends with a
 * `404` — a server that answers while the app is gone, as the upload relaunch's target is.
 */
private const val DOWNLOAD_RELAUNCH_TARGET = "https://snapsync.stho.net/contract-relaunch-download"

private const val BEGIN_AFTER_SECONDS = 15.0

/** What a replayed finish hands its owner for the temporary file: no file — the device's was gone once it returned. */
private const val REPLAYED_TEMP = "replayed:no-file"

// ---- the session as text ---------------------------------------------------------------------------------------

private fun openDownloadCall(identifier: String) = "open(id=$identifier)"

private const val ATTACHED = "attached"
private const val RELEASE_CALL = "release()"
private const val DONE = "done"
private const val DRAINED = "didFinishEvents()"

internal fun downloadHandleEvents(identifier: String) = "handleEvents(id=$identifier)"

private fun String.downloadField(key: String) = substringAfter("$key=").substringBefore(' ').removeSuffix(")")

private fun finishedEvent(task: DownloadTask, facts: TransferOutcome) =
    "didFinish(tag=${task.tag} status=${facts.statusCode} expected=${facts.expectedBytes} received=${facts.receivedBytes})"

private fun completedEvent(task: DownloadTask, error: NSError?) =
    "didComplete(tag=${task.tag} error=${error?.let { "${it.domain}/${it.code}" } ?: "none"})"

private fun invalidatedEvent(error: NSError?) = "didBecomeInvalid(error=${error?.let { "${it.domain}/${it.code}" } ?: "none"})"

private class ReplayedDownloadTask(override val tag: String?) : DownloadTask {
    override fun cancel() = Unit
}

private fun String.parseDownloadTask() = ReplayedDownloadTask(downloadField("tag").takeIf { it != "null" })

private fun String.parseDownloadError(): NSError? = downloadField("error").takeIf { it != "none" }?.let { text ->
    NSError.errorWithDomain(text.substringBeforeLast('/'), text.substringAfterLast('/').toLong(), null)
}

private fun String.parseFacts() = TransferOutcome(
    statusCode = downloadField("status").takeIf { it != "null" }?.toInt(),
    expectedBytes = downloadField("expected").toLong(),
    receivedBytes = downloadField("received").toLong(),
)

/** Passes every call to [real] and records it, with iOS's answer, and every event the session delivers back. */
internal class RecordingDownloadSessionApi(
    private val real: DownloadSessionApi,
    private val recorder: Recorder,
) : DownloadSessionApi {
    override fun open(identifier: String, events: DownloadSessionEvents): DownloadSession {
        val recorded = DownloadSessionEvents(
            finished = { task, facts, path ->
                recorder.event(finishedEvent(task, facts))
                events.finished(task, facts, path)
            },
            completed = { task, error ->
                recorder.event(completedEvent(task, error))
                events.completed(task, error)
            },
            invalidated = { error ->
                recorder.event(invalidatedEvent(error))
                events.invalidated(error)
            },
            drained = {
                recorder.event(DRAINED)
                events.drained()
            },
        )
        // Recorded before the session exists: what it delivers arrives on its own queue, and must follow the open.
        recorder.record(openDownloadCall(identifier), ATTACHED)
        return RecordingDownloadSession(real.open(identifier, recorded), recorder)
    }

    override fun release(handler: () -> Unit) {
        recorder.record(RELEASE_CALL, DONE)
        real.release(handler)
    }
}

private class RecordingDownloadSession(private val real: DownloadSession, private val recorder: Recorder) :
    DownloadSession {
    override fun download(request: NSURLRequest, tag: String) {
        real.download(request, tag)
        recorder.record("download(url=${request.URL?.absoluteString} tag=$tag)", "started")
    }

    override suspend fun tasks(): List<DownloadTask> =
        real.tasks().also { tasks -> recorder.record("tasks()", tasks.joinToString(",", "[", "]") { "tag=${it.tag}" }) }
}

/**
 * Answers every call from one clause's recorded block, exactly and in order, and delivers the session's recorded events
 * — after the call they followed, in their order, on another thread, as the session does.
 */
internal class ReplayingDownloadSessionApi(private val replayer: Replayer) : DownloadSessionApi {
    override fun open(identifier: String, events: DownloadSessionEvents): DownloadSession {
        replayer.answer(openDownloadCall(identifier))
        deliverDownloadsDue(replayer, events)
        return ReplayingDownloadSession(replayer, events)
    }

    override fun release(handler: () -> Unit) {
        replayer.answer(RELEASE_CALL)
        handler()
    }
}

private fun deliverDownloadsDue(replayer: Replayer, events: DownloadSessionEvents) {
    val due = replayer.takeEvents()
    if (due.isEmpty()) return
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
        due.forEach { event ->
            when {
                event == DRAINED -> events.drained()
                event.startsWith("didFinish(") ->
                    events.finished(event.parseDownloadTask(), event.parseFacts(), REPLAYED_TEMP)
                event.startsWith("didComplete(") ->
                    events.completed(event.parseDownloadTask(), event.parseDownloadError())
                event.startsWith("didBecomeInvalid(") -> events.invalidated(event.parseDownloadError())
                else -> throw Divergence("a recorded session event this replay does not know: $event")
            }
        }
    }
}

private class ReplayingDownloadSession(private val replayer: Replayer, private val events: DownloadSessionEvents) :
    DownloadSession {
    override fun download(request: NSURLRequest, tag: String) {
        replayer.answer("download(url=${request.URL?.absoluteString} tag=$tag)")
        deliverDownloadsDue(replayer, events)
    }

    override suspend fun tasks(): List<DownloadTask> {
        val answer = replayer.answer("tasks()").removePrefix("[").removeSuffix("]")
        deliverDownloadsDue(replayer, events)
        return answer.split(',').filter { it.isNotEmpty() }.map { "$it ".parseDownloadTask() }
    }
}

// ---- the subject ---------------------------------------------------------------------------------------------------

/**
 * The downloader over [api] in the relaunched state: [DownloadUnderTest.open] answers the one bare adapter the
 * relaunch is handed to, through its recording proxy on [log], and [DownloadUnderTest.relaunch] hands it the
 * relaunch with [handler] — the system's completion handler on a device, nothing on a replay. Shared by the device
 * binding and its replay, so both make identical calls.
 */
internal fun relaunchedDownload(
    api: DownloadSessionApi,
    handler: () -> Unit,
    log: CallLog,
    beforeDeliver: () -> Unit = {},
    afterDispose: () -> Unit = {},
): Entered<DownloadUnderTest> {
    val bare = IosDownload(Logger.withTag("contract"), api, DOWNLOAD_CONTRACT_SESSION)
    return Entered.Ready(
        DownloadUnderTest(
            open = { bare.recorded(log) },
            base = DOWNLOAD_RELAUNCH_TARGET.substringBefore("/contract"),
            readTemp = { path -> NSData.dataWithContentsOfFile(path)?.toByteArray() },
            relaunch = {
                beforeDeliver()
                // The OS's relaunch, which no port call carries: handed to the bare adapter.
                bare.handleEvents(handler)
            },
        ),
        dispose = afterDispose,
    )
}

/** The downloader in the app the system relaunched on a device, recording every call and every event. */
internal class DeviceRelaunchedDownloadBinding(
    private val recorder: Recorder,
    private val handler: () -> Unit,
) : Binding<DownloadState, DownloadUnderTest> {
    override val host = Host.IOS_DEVICE_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(DownloadState.RELAUNCHED_WITH_EVENTS)

    override fun create(state: DownloadState, clauseId: String, log: CallLog): Entered<DownloadUnderTest> {
        if (state !in reaches) return Entered.Unreachable("this run records a relaunch; $state runs live")
        recorder.open(clauseId)
        // The system's call that started this process — before the clause runs, so first in the block.
        recorder.event(downloadHandleEvents(DOWNLOAD_CONTRACT_SESSION))
        return relaunchedDownload(
            RecordingDownloadSessionApi(SystemDownloadSessionApi(Logger.withTag("contract")), recorder),
            handler,
            log,
        )
    }
}

// ---- the run, across two processes -------------------------------------------------------------------------------

/**
 * `POST /contract/Download` on the device app: `?step=arm` prepares the relaunch and exits the app; `?step=collect`
 * answers what the relaunched process recorded.
 */
internal fun downloadRelaunchContracts(): List<InAppContract> = listOf(
    InAppContract(DownloadContract.name, Host.IOS_DEVICE_APP) { params ->
        when (params["step"]) {
            "arm" -> armDownloadRelaunch()
            "collect" -> keptRecording(DownloadContract.name) ?: (
                CONTRACT_REFUSED + "no relaunch has recorded ${DownloadContract.name} yet: POST " +
                    "/contract/${DownloadContract.name}?step=arm, wait for iOS to relaunch the app in the background, " +
                    "then open it and collect.\n"
                )
            else ->
                CONTRACT_REFUSED +
                    "the relaunch is recorded in two steps: ?step=arm (the app exits; iOS relaunches it in the background " +
                    "about a minute later), then ?step=collect once the app is open again.\n"
        }
    },
)

/**
 * Process one: a transfer prepared in [DOWNLOAD_CONTRACT_SESSION] to begin after the app is gone, then the app exits —
 * not force-quit, which cancels a session's transfers and is never relaunched for. The answer is a refusal on purpose:
 * an arm is not a recording.
 */
private fun armDownloadRelaunch(): String {
    if (onSimulator()) {
        return CONTRACT_REFUSED + "this process is a simulator app, whose sessions never outlive it; arm on a device.\n"
    }
    keptPath(DownloadContract.name)?.let { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    val session = NSURLSession.sessionWithConfiguration(transferSessionConfiguration(DOWNLOAD_CONTRACT_SESSION))
    // A transfer an earlier arm left — still retrying, or never relaunched for — must not answer this run's relaunch.
    val cleared = dispatch_semaphore_create(0)
    session.getAllTasksWithCompletionHandler { tasks ->
        tasks?.forEach { (it as? NSURLSessionTask)?.cancel() }
        dispatch_semaphore_signal(cleared)
    }
    dispatch_semaphore_wait(cleared, dispatch_time(DISPATCH_TIME_NOW, (5 * NSEC_PER_SEC.toLong())))
    val task = session.downloadTaskWithRequest(NSMutableURLRequest(NSURL(string = DOWNLOAD_RELAUNCH_TARGET)))
    task.taskDescription = DownloadContract.RELAUNCHED_TAG
    task.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(BEGIN_AFTER_SECONDS)
    task.resume()
    // The daemon has the transfer once the session lists it; only then may the process go.
    val listed = dispatch_semaphore_create(0)
    var count = 0
    session.getAllTasksWithCompletionHandler { tasks ->
        count = tasks?.count { it is NSURLSessionTask } ?: 0
        dispatch_semaphore_signal(listed)
    }
    dispatch_semaphore_wait(listed, dispatch_time(DISPATCH_TIME_NOW, (5 * NSEC_PER_SEC.toLong())))
    if (count == 0) return CONTRACT_REFUSED + "the session lists no transfer: nothing will relaunch the app\n"
    dispatch_after(
        dispatch_time(DISPATCH_TIME_NOW, (2 * NSEC_PER_SEC.toLong())),
        dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u),
    ) { exit(0) }
    return CONTRACT_REFUSED +
        "armed (not a recording): the app exits now; iOS starts the transfer in ${BEGIN_AFTER_SECONDS.toInt()} s, the " +
        "server answers 404, and iOS relaunches the app in the background to deliver that. Open the app after a minute, " +
        "then POST /contract/${DownloadContract.name}?step=collect.\n"
}

/**
 * Called as a rig build's adapter set is built — before the composition's first relaunch is routed — so the relaunch
 * [armDownloadRelaunch] causes reaches [runDownloadRelaunched] rather than production's download session.
 */
internal fun claimDownloadContractSession() = BackgroundSessions.claim(DOWNLOAD_CONTRACT_SESSION) { handler ->
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
        whileHoldingBackgroundTime("contract.download.relaunch") { runDownloadRelaunched(handler) }
    }
}

/** Process two: the relaunched app records the clause and keeps the recording. */
private fun runDownloadRelaunched(handler: () -> Unit) {
    val recorder = Recorder()
    val results = run(
        single(DownloadContract, DOWNLOAD_RELAUNCH_CLAUSE),
        DeviceRelaunchedDownloadBinding(recorder, handler),
    )
    val env = deviceDiagnosticEnvironment(uploadTier = "n/a")
    val header = listOf(
        "contract" to DownloadContract.name,
        "host" to Host.IOS_DEVICE_APP.name,
        "file" to recordingName(DownloadContract.name, Host.IOS_DEVICE_APP, null) + ".rec",
        "device" to env.deviceModel,
        "os" to env.osVersion,
        "build" to env.buildNumber,
        "kotlin" to KotlinVersion.CURRENT.toString(),
        "recorded" to NSISO8601DateFormatter().stringFromDate(NSDate()),
    ) + results.map { "live ${it.clauseId}" to it.outcome.render() }
    keepRecording(DownloadContract.name, null, null, recorder.recording(header).render())
}
