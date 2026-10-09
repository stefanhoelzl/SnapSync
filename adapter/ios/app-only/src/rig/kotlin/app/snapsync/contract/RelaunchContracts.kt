@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalAtomicApi::class)

package app.snapsync.contract

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Clause
import app.snapsync.contracts.Contract
import app.snapsync.contracts.Divergence
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FixtureObjects
import app.snapsync.contracts.Host
import app.snapsync.contracts.InAppContract
import app.snapsync.contracts.Recorder
import app.snapsync.contracts.Relaunch
import app.snapsync.contracts.Replayer
import app.snapsync.contracts.SharePresenterContract
import app.snapsync.contracts.SharePresenterState
import app.snapsync.contracts.UploadContract
import app.snapsync.contracts.UploadState
import app.snapsync.contracts.UploadUnderTest
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.recordingName
import app.snapsync.contracts.render
import app.snapsync.contracts.run
import app.snapsync.ios.urlsession.BackgroundSessions
import app.snapsync.ios.urlsession.IosUrlSessionUploadPlatform
import app.snapsync.ios.urlsession.SessionEvents
import app.snapsync.ios.urlsession.SystemUploadSessionApi
import app.snapsync.ios.urlsession.TransferTask
import app.snapsync.ios.urlsession.UploadSession
import app.snapsync.ios.urlsession.UploadSessionApi
import app.snapsync.ios.urlsession.transferSessionConfiguration
import app.snapsync.link.SystemUrlOpenerApi
import app.snapsync.logging.deviceDiagnosticEnvironment
import app.snapsync.logging.documentsDirectory
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadSource
import app.snapsync.ports.Completion
import app.snapsync.ports.SystemUi
import app.snapsync.ports.UploadHandlers
import app.snapsync.systemui.IosSystemUi
import app.snapsync.systemui.ShareSheetApi
import app.snapsync.systemui.SystemShareSheetApi
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.setHTTPMethod
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
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
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/*
 * The RELAUNCH run: what the operating system delivers to an app it launched in the background for a session's events,
 * recorded across the two processes it takes (`docs/testing.md`, "Record and replay"), and replayed on every build.
 *
 * The background `URLSession` as TEXT — its calls, the events it delivers, the release of the relaunch's handler — and
 * the share sheet with no window to present from, which the same background launch is the one state of.
 *
 * Compiled into this module's `iosMain` only under `-Psnapsync.rig=true` (where the device records), and into `iosTest`
 * otherwise (where CI replays) — one file, so the recorder and the replayer cannot spell a call differently.
 */

/** The session the run prepares its transfer in, apart from production's: claimed in a rig build ([claimContractSessions]). */
internal const val CONTRACT_SESSION = "app.snapsync.contract.upload.relaunch"

/** The one clause the relaunch records, alone in its run. */
internal const val RELAUNCH_CLAUSE = "RELAUNCHED_EVENTS_ARE_HANDED_OVER_THEN_DRAINED"

/**
 * Where the prepared transfer goes: a path of this project's own domain that nothing serves, so it ends with a `404` and
 * stores nothing. It must be a server that answers while the app is gone — measured on the SE2 (iOS 26.6.2,
 * 2026-10-08): a transfer whose connection fails is RETRIED by the system (`will be retried after error … -1001`) and
 * never ends, so a dead loopback port relaunches nothing. Created to begin after the app has exited
 * ([BEGIN_AFTER_SECONDS]), so its end is delivered by a relaunch.
 */
private const val RELAUNCH_TARGET = "https://snapsync.stho.net/contract-relaunch"

private const val BEGIN_AFTER_SECONDS = 15.0

// ---- the session as text ---------------------------------------------------------------------------------------

private fun openCall(identifier: String) = "open(id=$identifier)"

private const val ATTACHED = "attached"
private const val RELEASE_CALL = "release()"
private const val DONE = "done"
private const val DRAINED = "didFinishEvents()"

private fun handleEvents(identifier: String) = "handleEvents(id=$identifier)"

private fun TransferTask.render() = "tag=$tag path=$path"

private fun completed(task: TransferTask, status: Long, error: NSError?) =
    "didComplete(${task.render()} status=$status error=${error?.let { "${it.domain}/${it.code}" } ?: "none"})"

private fun String.field(key: String) = substringAfter("$key=").substringBefore(' ').removeSuffix(")")

private class ReplayedTask(override val tag: String?, override val path: String?) : TransferTask {
    override fun cancel() = Unit
}

private fun String.parseTask() = ReplayedTask(
    field("tag").takeIf {
        it != "null"
    },
    field("path").takeIf { it != "null" },
)

private fun String.parseError(): NSError? = field("error").takeIf { it != "none" }?.let { text ->
    NSError.errorWithDomain(text.substringBeforeLast('/'), text.substringAfterLast('/').toLong(), null)
}

/** Passes every call to [real] and records it, with iOS's answer, and every event the session delivers back. */
internal class RecordingUploadSessionApi(
    private val real: UploadSessionApi,
    private val recorder: Recorder,
) : UploadSessionApi {
    override fun open(identifier: String, events: SessionEvents): UploadSession {
        val recorded = SessionEvents(
            completed = { task, status, error ->
                recorder.event(completed(task, status, error))
                events.completed(task, status, error)
            },
            drained = {
                recorder.event(DRAINED)
                events.drained()
            },
        )
        // Recorded before the session exists: what it delivers arrives on its own queue, and must follow the open.
        recorder.record(openCall(identifier), ATTACHED)
        return RecordingUploadSession(real.open(identifier, recorded), recorder)
    }

    override fun release(handler: () -> Unit) {
        recorder.record(RELEASE_CALL, DONE)
        real.release(handler)
    }
}

private class RecordingUploadSession(private val real: UploadSession, private val recorder: Recorder) : UploadSession {
    override fun upload(request: NSURLRequest, file: String, tag: String) {
        real.upload(request, file, tag)
        recorder.record("upload(url=${request.URL?.absoluteString} tag=$tag)", "started")
    }

    override suspend fun tasks(): List<TransferTask> =
        real.tasks().also { tasks -> recorder.record("tasks()", tasks.joinToString(",", "[", "]") { it.render() }) }
}

/**
 * Answers every call from one clause's recorded block, exactly and in order, and delivers the session's recorded events
 * — after the call they followed, in their order, on another thread, as the session does.
 */
internal class ReplayingUploadSessionApi(private val replayer: Replayer) : UploadSessionApi {
    override fun open(identifier: String, events: SessionEvents): UploadSession {
        replayer.answer(openCall(identifier))
        deliver(events)
        return ReplayingUploadSession(replayer, events)
    }

    override fun release(handler: () -> Unit) {
        replayer.answer(RELEASE_CALL)
        handler()
    }

    private fun deliver(events: SessionEvents) = deliverDue(replayer, events)
}

private fun deliverDue(replayer: Replayer, events: SessionEvents) {
    val due = replayer.takeEvents()
    if (due.isEmpty()) return
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
        due.forEach { event ->
            when {
                event == DRAINED -> events.drained()
                event.startsWith("didComplete(") ->
                    events.completed(event.parseTask(), event.field("status").toLong(), event.parseError())
                else -> throw Divergence("a recorded session event this replay does not know: $event")
            }
        }
    }
}

private class ReplayingUploadSession(private val replayer: Replayer, private val events: SessionEvents) : UploadSession {
    override fun upload(request: NSURLRequest, file: String, tag: String) {
        replayer.answer("upload(url=${request.URL?.absoluteString} tag=$tag)")
        deliverDue(replayer, events)
    }

    override suspend fun tasks(): List<TransferTask> {
        val answer = replayer.answer("tasks()").removePrefix("[").removeSuffix("]")
        deliverDue(replayer, events)
        return answer.split(',').filter { it.isNotEmpty() }.map { "$it ".parseTask() }
    }
}

// ---- the subject ---------------------------------------------------------------------------------------------------

/**
 * The URLSession uploader over [api] in the relaunched state, listened to by the binding through its recording proxy
 * on [log]; [deliver] hands the bare adapter the relaunch with [handler] — the system's completion handler on a device,
 * nothing on a replay. Shared by the device binding and its replay, so both make identical calls.
 */
internal fun relaunchedUpload(
    api: UploadSessionApi,
    handler: () -> Unit,
    log: CallLog,
    beforeDeliver: () -> Unit = {},
    afterDispose: () -> Unit = {},
): Entered<UploadUnderTest> {
    val bare = IosUrlSessionUploadPlatform(Logger.withTag("contract"), CONTRACT_SESSION, cap = 4, api = api)
    val platform = bare.recorded(log)
    val ended = AtomicReference<List<UploadJob>>(emptyList())
    val handedOver = AtomicReference<List<Completion>>(emptyList())
    val drains = AtomicInt(0)
    platform.listen(
        UploadHandlers(
            onFinished = { job -> ended.append(job) },
            onBackgroundEvents = { completion -> handedOver.append(completion) },
            onEventsDrained = { drains.incrementAndFetch() },
        ),
    )
    return Entered.Ready(
        UploadUnderTest(
            upload = platform,
            base = RELAUNCH_TARGET.substringBefore("/contract"),
            usable = { error("the relaunched state creates nothing") },
            unusable = { UploadSource.Resource(Unit) },
            ended = { ended.load() },
            objects = FixtureObjects { null },
            relaunch = Relaunch(
                deliver = {
                    beforeDeliver()
                    // The OS's relaunch, which no port call carries: handed to the bare adapter.
                    bare.handleEvents(handler)
                },
                handedOver = { handedOver.load() },
                drains = { drains.load() },
            ),
        ),
        dispose = afterDispose,
    )
}

private fun <T> AtomicReference<List<T>>.append(item: T) {
    while (true) {
        val now = load()
        if (compareAndSet(now, now + item)) return
    }
}

/** The URLSession uploader in the app the system relaunched on a device, recording every call and every event. */
internal class DeviceRelaunchedUploadBinding(
    private val recorder: Recorder,
    private val handler: () -> Unit,
) : Binding<UploadState, UploadUnderTest> {
    override val host = Host.IOS_DEVICE_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(UploadState.RELAUNCHED_WITH_EVENTS)

    override fun create(state: UploadState, clauseId: String, log: CallLog): Entered<UploadUnderTest> {
        if (state !in reaches) {
            return Entered.Unreachable(
                "this run records a relaunch; $state runs live on the simulator app",
            )
        }
        recorder.open(clauseId)
        // The system's call that started this process — before the clause runs, so first in the block.
        recorder.event(handleEvents(CONTRACT_SESSION))
        return relaunchedUpload(
            RecordingUploadSessionApi(SystemUploadSessionApi(Logger.withTag("contract")), recorder),
            handler,
            log,
        )
    }
}

// ---- the share sheet, with no window -----------------------------------------------------------------------------

private fun presentCall(text: String, title: String) = "present(text=$text title=$title)"

private const val SHOWN = "shown"
private const val NOTHING_ON_SCREEN = "nothing on screen"

/** Passes every presentation to [real] and records it, with iOS's answer. */
internal class RecordingShareSheetApi(private val real: ShareSheetApi, private val recorder: Recorder) : ShareSheetApi {
    override fun present(text: String, title: String, answered: (Boolean) -> Unit) =
        real.present(text, title) { shown ->
            recorder.record(presentCall(text, title), if (shown) SHOWN else NOTHING_ON_SCREEN)
            answered(shown)
        }
}

/** Answers every presentation from one clause's recorded block, exactly and in order. */
internal class ReplayingShareSheetApi(private val replayer: Replayer) : ShareSheetApi {
    override fun present(text: String, title: String, answered: (Boolean) -> Unit) =
        answered(replayer.answer(presentCall(text, title)) == SHOWN)
}

/** The real [IosSystemUi] in an app the system launched in the background, never brought to the front. */
internal class DeviceNoWindowShareBinding(private val recorder: Recorder) : Binding<SharePresenterState, SystemUi> {
    override val host = Host.IOS_DEVICE_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(SharePresenterState.NO_WINDOW)

    override fun create(state: SharePresenterState, clauseId: String, log: CallLog): Entered<SystemUi> {
        if (state !in reaches) {
            return Entered.Unreachable(
                "this run is a background launch; $state runs live on the simulator app",
            )
        }
        // Measured (SE2, iOS 26.6.2): a background launch connects the app's scene too — so what this run holds is the
        // app in the background, never brought to the front; whether that leaves a window to present from is the
        // adapter's answer, recorded.
        if (!appInBackground()) return Entered.Unreachable("the app was brought to the front during the relaunch")
        recorder.open(clauseId)
        return Entered.Ready(
            IosSystemUi(SystemUrlOpenerApi, RecordingShareSheetApi(SystemShareSheetApi, recorder)).recorded(log),
        )
    }
}

// ---- the run, across two processes -------------------------------------------------------------------------------

internal fun keptPath(contract: String) =
    documentsDirectory()?.let { "$it/contracts/${recordingName(contract, Host.IOS_DEVICE_APP, null)}.rec" }

internal fun onSimulator() = NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null

/**
 * `POST /contract/Upload` on the device app: `?step=arm` prepares the relaunch and exits the app; `?step=collect`
 * answers what the relaunched process recorded. `POST /contract/SharePresenter` collects the share sheet's, which the
 * same relaunch records.
 */
internal fun relaunchContracts(): List<InAppContract> = listOf(
    InAppContract(UploadContract.name, Host.IOS_DEVICE_APP) { params ->
        when (params["step"]) {
            "arm" -> armRelaunch()
            "collect" -> collect(UploadContract.name)
            else ->
                CONTRACT_REFUSED +
                    "the relaunch is recorded in two steps: ?step=arm (the app exits; iOS relaunches it in the background " +
                    "about a minute later), then ?step=collect once the app is open again.\n"
        }
    },
    InAppContract(SharePresenterContract.name, Host.IOS_DEVICE_APP) { collect(SharePresenterContract.name) },
)

/** What a run in an earlier process kept for [contract] ([keepRecording]), or `null`. */
internal fun keptRecording(contract: String): String? =
    keptPath(contract)?.let { NSString.stringWithContentsOfFile(it, NSUTF8StringEncoding, null) }

internal fun collect(contract: String): String {
    val text = keptRecording(contract)
    return text ?: (
        CONTRACT_REFUSED + "no relaunch has recorded $contract yet: POST /contract/Upload?step=arm, wait for iOS to " +
            "relaunch the app in the background, then open it and collect.\n"
        )
}

/**
 * Process one: a transfer prepared in [CONTRACT_SESSION] to begin after the app is gone, then the app exits — not
 * force-quit, which cancels a session's transfers and is never relaunched for. The answer is a refusal on purpose: an
 * arm is not a recording.
 */
private fun armRelaunch(): String {
    if (onSimulator()) {
        return CONTRACT_REFUSED + "this process is a simulator app, whose sessions never outlive it; arm on a device.\n"
    }
    val dir = documentsDirectory()?.let { "$it/contracts" } ?: return CONTRACT_REFUSED + "no Documents directory\n"
    NSFileManager.defaultManager.createDirectoryAtPath(
        dir,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    listOf(UploadContract.name, SharePresenterContract.name).forEach { contract ->
        keptPath(contract)?.let { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }
    val body = "$dir/relaunch.bin"
    NSString.create(
        string = "relaunch",
    ).writeToFile(body, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    val session = NSURLSession.sessionWithConfiguration(transferSessionConfiguration(CONTRACT_SESSION))
    // A transfer an earlier arm left — still retrying, or never relaunched for — must not answer this run's relaunch.
    val cleared = dispatch_semaphore_create(0)
    session.getAllTasksWithCompletionHandler { tasks ->
        tasks?.forEach { (it as? NSURLSessionTask)?.cancel() }
        dispatch_semaphore_signal(cleared)
    }
    dispatch_semaphore_wait(cleared, dispatch_time(DISPATCH_TIME_NOW, (5 * NSEC_PER_SEC.toLong())))
    val request = NSMutableURLRequest(NSURL(string = RELAUNCH_TARGET)).apply { setHTTPMethod("PUT") }
    val task = session.uploadTaskWithRequest(request, fromFile = NSURL.fileURLWithPath(body))
    task.taskDescription = UploadContract.relaunchedTag(RELAUNCH_CLAUSE)
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
        "server answers 404, and iOS relaunches the app in the background to deliver that. Open the app after a minute, then " +
        "POST /contract/Upload?step=collect and /contract/SharePresenter.\n"
}

/**
 * Called as a rig build's adapter set is built — before the composition's first relaunch is routed — so the relaunch
 * [armRelaunch] causes reaches [runRelaunched] rather than the download session every unknown identifier goes to.
 */
internal fun claimContractSession() = BackgroundSessions.claim(CONTRACT_SESSION) { handler ->
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
        whileHoldingBackgroundTime("contract.relaunch") { runRelaunched(handler) }
    }
}

/** Process two: the relaunched app records the clause, then the share sheet with no window, keeping both. */
private fun runRelaunched(handler: () -> Unit) {
    val recorder = Recorder()
    val results = run(single(UploadContract, RELAUNCH_CLAUSE), DeviceRelaunchedUploadBinding(recorder, handler))
    val env = deviceDiagnosticEnvironment(uploadTier = "n/a")
    val header = listOf(
        "contract" to UploadContract.name,
        "host" to Host.IOS_DEVICE_APP.name,
        "file" to recordingName(UploadContract.name, Host.IOS_DEVICE_APP, null) + ".rec",
        "device" to env.deviceModel,
        "os" to env.osVersion,
        "build" to env.buildNumber,
        "kotlin" to KotlinVersion.CURRENT.toString(),
        "recorded" to NSISO8601DateFormatter().stringFromDate(NSDate()),
    ) + results.map { "live ${it.clauseId}" to it.outcome.render() }
    keepRecording(UploadContract.name, null, null, recorder.recording(header).render())
    recordAppOnDevice(SharePresenterContract, null) { DeviceNoWindowShareBinding(it) }
}

/** [contract] reduced to its clause [id]. */
internal fun <K : Enum<K>, T> single(contract: Contract<K, T>, id: String) = object : Contract<K, T>(contract.name) {
    override val clauses: List<Clause<K, T>> = contract.clauses.filter { it.id == id }
}
