@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.download

import app.snapsync.ios.upload.applyTransferNetwork
import app.snapsync.ios.urlsession.SessionCompletion
import app.snapsync.ios.urlsession.transferSessionConfiguration
import app.snapsync.logging.invocation
import app.snapsync.model.StartResult
import app.snapsync.model.TransferNetwork
import app.snapsync.model.TransferOutcome
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import co.touchlab.kermit.Logger
import platform.Foundation.NSError
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Background-session identifier — stable so an app relaunch reconnects to the same transfers. Honoured on every
 * shipped binary; inert on `iosSimulatorArm64`, whose default configuration ignores it ([transferSessionConfiguration]).
 */
const val DOWNLOAD_SESSION_ID = "app.snapsync.download.bg"

/**
 * The iOS [Download]: a `URLSession` (non-discretionary; Wi-Fi and cellular unless a transfer's own request holds it
 * to an unrestricted network) which on
 * every shipped binary is a **background** session that keeps downloading while the app is suspended and relaunches it
 * on completion. The binding is fixed by the compilation target — see [transferSessionConfiguration] for what
 * `iosSimulatorArm64` gets instead, and for the properties a run on that target does **not** evidence.
 *
 * Thin since phase 11f: it reports a finished body's facts and its temporary file to the registered handlers, which
 * judge and move it before the callback returns — the port's `onFinished` is inline for that reason. The queue, the
 * window, the tag codec and the staging path are the download feature's.
 *
 * **It never invalidates the session.** Cancellation is [cancelAll], task by task; the session outlives every leave.
 * If the *system* invalidates it, every transfer it still held is reported completed with an error — as a cancelled one
 * is — and the next [start] builds a fresh session: creating a task on an invalidated session raises an `NSException`
 * Kotlin/Native cannot catch.
 */
class IosDownload internal constructor(
    private val log: Logger,
    /** The session boundary: the system's, or a recording's ([DownloadSessionApi]). */
    private val api: DownloadSessionApi,
    /** The session it brings up: production's, or one a contract run keeps apart. */
    private val identifier: String = DOWNLOAD_SESSION_ID,
) : Download {

    constructor(log: Logger = Logger.withTag("Download")) : this(log, SystemDownloadSessionApi(log))

    private val handlers = AtomicReference<DownloadHandlers?>(null)

    /** The live session, or `null` until one is wanted — or after the system invalidated the last one. */
    private val current = AtomicReference<DownloadSession?>(null)

    /** The tags of the transfers started or reported here and not yet completed — what an invalidation ends. */
    private val open = AtomicReference<Set<String>>(emptySet())

    override fun listen(handlers: DownloadHandlers) {
        this.handlers.store(handlers)
    }

    /**
     * The session, built when first wanted: on the first transfer, or on a `handleEventsForBackgroundURLSession`
     * relaunch, where it must exist for the OS to deliver the pending completions to its delegate. Built from
     * [transferSessionConfiguration] — background on every shipped binary, default on `iosSimulatorArm64` — whose
     * policy (cellular allowed, no discretionary deferral, relaunch on completion) is declared there; a transfer's own
     * request narrows the networks it may use ([start]).
     */
    private fun session(): DownloadSession {
        current.load()?.let { return it }
        val built = AtomicReference<DownloadSession?>(null)
        val session = api.open(
            identifier,
            DownloadSessionEvents(
                finished = { task, facts, path -> onFinished(task, facts, path) },
                completed = { task, error -> onComplete(task, error) },
                invalidated = { error -> built.load()?.let { onInvalidated(it, error) } },
                drained = { onEventsFinished() },
            ),
        )
        built.store(session)
        return if (current.compareAndSet(null, session)) session else checkNotNull(current.load())
    }

    override fun start(url: String, tag: String, network: TransferNetwork): StartResult {
        val nsUrl = NSURL.URLWithString(url) ?: return StartResult.NotStarted
        // The member's mobile-data choice rides on the request, so this transfer keeps the rule it started
        // with while the one session carries others under another.
        val request = NSMutableURLRequest(uRL = nsUrl).apply { applyTransferNetwork(network) }
        val session = session()
        update { it + tag }
        session.download(request, tag)
        return StartResult.Started
    }

    /** Cancels every task the session holds — ones a relaunched process inherited included — and returns once done. */
    override suspend fun cancelAll() = log.invocation("download.cancelAll") {
        val tasks = session().tasks()
        tasks.forEach { it.cancel() }
        log.i { "cancelled ${tasks.size} download task(s)" }
    }

    /**
     * The operating system relaunched (or woke) the app to deliver this session's events. The handler is handed over
     * FIRST, then the session is brought up — so every event it then delivers, and the drain report after them, land in
     * that handler's window (adopt, then create).
     */
    fun handleEvents(completion: () -> Unit) = log.invocation("download.handleEvents") {
        val registered = handlers.load()
        if (registered == null) {
            log.e { "download session events arrived before the composition listened — released at once" }
            SessionCompletion(completion, api).complete()
        } else {
            registered.onBackgroundEvents(SessionCompletion(completion, api))
        }
        session()
        Unit
    }

    private fun onFinished(task: DownloadTask, facts: TransferOutcome, temp: String) {
        val tag = task.tag ?: return
        update { it + tag }
        handlers.load()?.onFinished?.invoke(tag, facts, temp)
            ?: log.e { "download $tag finished before the composition listened — its bytes are downloaded again later" }
    }

    private fun onComplete(task: DownloadTask, error: NSError?) {
        val tag = task.tag ?: return
        update { it - tag }
        handlers.load()?.onCompleted?.invoke(tag, error?.localizedDescription)
    }

    /**
     * The system invalidated [session] (we never do): forget it, so the next transfer builds a fresh one, and end every
     * transfer it still held — each reported completed with an error, as a cancelled one is, so its slot is free.
     */
    private fun onInvalidated(session: DownloadSession, error: NSError?) {
        current.compareAndSet(session, null)
        val ended = open.exchange(emptySet())
        val reason = "the session was invalidated by the system: ${error?.localizedDescription ?: "no error"}"
        ended.forEach { tag -> handlers.load()?.onCompleted?.invoke(tag, reason) }
    }

    private fun update(change: (Set<String>) -> Set<String>) {
        while (true) {
            val now = open.load()
            if (open.compareAndSet(now, change(now))) return
        }
    }

    private fun onEventsFinished() {
        handlers.load()?.onEventsDrained?.invoke()
    }
}
