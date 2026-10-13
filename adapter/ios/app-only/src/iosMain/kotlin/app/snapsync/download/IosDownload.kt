@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.download

import app.snapsync.inbound.CompositionOnly
import app.snapsync.ios.upload.applyTransferNetwork
import app.snapsync.ios.urlsession.DrainCount
import app.snapsync.ios.urlsession.SessionCompletion
import app.snapsync.ios.urlsession.transferSessionConfiguration
import app.snapsync.ports.CompletedDownload
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import app.snapsync.ports.FinishedDownload
import app.snapsync.ports.SessionDrain
import app.snapsync.ports.SessionRelaunch
import app.snapsync.ports.StartResult
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.TransferOutcome
import co.touchlab.kermit.Logger
import platform.Foundation.NSError
import platform.Foundation.NSLock
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.setValue
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

    /** The session's drain reports, counted for the relaunches that wait for them. */
    private val drains = DrainCount()

    @CompositionOnly
    override fun inbound(handlers: DownloadHandlers) {
        this.handlers.store(handlers)
    }

    /**
     * The session, built when first wanted: on the first transfer, or on a `handleEventsForBackgroundURLSession`
     * relaunch, where it must exist for the OS to deliver the pending completions to its delegate. Built from
     * [transferSessionConfiguration] — background on every shipped binary, default on `iosSimulatorArm64` — whose
     * policy (cellular allowed, no discretionary deferral, relaunch on completion) is declared there; a transfer's own
     * request narrows the networks it may use ([start]).
     *
     * Built under [building], so one process opens ONE session for its identifier: two callers racing here would
     * otherwise each open one — the loser's left to deliver events nobody reads — and the loser's re-read of the
     * winner could find it already invalidated.
     */
    private fun session(): DownloadSession {
        current.load()?.let { return it }
        building.lock()
        try {
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
            current.store(session)
            return session
        } finally {
            building.unlock()
        }
    }

    /** Held while a session is built ([session]). */
    private val building = NSLock()

    override fun start(url: String, headers: Map<String, String>, tag: String, network: TransferNetwork): StartResult {
        val nsUrl = NSURL.URLWithString(url) ?: return StartResult.NotStarted
        // The member's mobile-data choice rides on the request, so this transfer keeps the rule it started
        // with while the one session carries others under another.
        val request = NSMutableURLRequest(uRL = nsUrl).apply { applyTransferNetwork(network) }
        // What the backend asks of every request — the version this build declares — rides along.
        headers.forEach { (name, value) -> request.setValue(value, forHTTPHeaderField = name) }
        val session = session()
        update { it + tag }
        session.download(request, tag)
        return StartResult.Started
    }

    /** Cancels every task the session holds — ones a relaunched process inherited included — and returns once done. */
    override suspend fun cancelAll() = run {
        val tasks = session().tasks()
        tasks.forEach { it.cancel() }
        log.i { "cancelled ${tasks.size} download task(s)" }
    }.also { log.i { "download.cancelAll" } }

    /**
     * The operating system relaunched (or woke) the app to deliver this session's events. The handler is handed over
     * FIRST, with the count of drain reports so far, then the session is brought up — so every event it then delivers,
     * and the drain report after them, land after the relaunch the core waits on.
     */
    fun handleEvents(completion: () -> Unit) {
        val registered = handlers.load()
        if (registered == null) {
            log.e { "download session events arrived before the composition registered — released at once" }
            SessionCompletion(completion, api).complete()
        } else {
            registered.onBackgroundEvents.handle(SessionRelaunch(drains.now), SessionCompletion(completion, api))
        }
        session()
        log.i { "download.handleEvents" }
    }

    private fun onFinished(task: DownloadTask, facts: TransferOutcome, temp: String) {
        val tag = task.tag ?: return
        update { it + tag }
        handlers.load()?.onFinished?.deliver(FinishedDownload(tag, facts, temp))
            ?: log.e { "download $tag finished before the composition listened — its bytes are downloaded again later" }
    }

    private fun onComplete(task: DownloadTask, error: NSError?) {
        val tag = task.tag ?: return
        update { it - tag }
        handlers.load()?.onCompleted?.deliver(CompletedDownload(tag, error?.localizedDescription))
    }

    /**
     * The system invalidated [session] (we never do): forget it, so the next transfer builds a fresh one, and end every
     * transfer it still held — each reported completed with an error, as a cancelled one is, so its slot is free.
     */
    private fun onInvalidated(session: DownloadSession, error: NSError?) {
        current.compareAndSet(session, null)
        val ended = open.exchange(emptySet())
        val reason = "the session was invalidated by the system: ${error?.localizedDescription ?: "no error"}"
        ended.forEach { tag -> handlers.load()?.onCompleted?.deliver(CompletedDownload(tag, reason)) }
    }

    private fun update(change: (Set<String>) -> Set<String>) {
        while (true) {
            val now = open.load()
            if (open.compareAndSet(now, change(now))) return
        }
    }

    private fun onEventsFinished() {
        handlers.load()?.onEventsDrained?.deliver(SessionDrain(drains.next()))
    }
}
