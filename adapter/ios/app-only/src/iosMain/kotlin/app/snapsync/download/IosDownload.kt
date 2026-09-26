@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.download

import app.snapsync.ios.urlsession.SessionCompletion
import app.snapsync.ios.urlsession.transferSessionConfiguration
import app.snapsync.logging.invocation
import app.snapsync.model.PlatformEntry
import app.snapsync.model.StartResult
import app.snapsync.model.TransferOutcome
import app.snapsync.objc.checkedObjCValue
import app.snapsync.objc.objcBoundary
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.darwin.NSObject
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume

/**
 * Background-session identifier — stable so an app relaunch reconnects to the same transfers. Honoured on every
 * shipped binary; inert on `iosSimulatorArm64`, whose default configuration ignores it ([transferSessionConfiguration]).
 */
const val DOWNLOAD_SESSION_ID = "app.snapsync.download.bg"

/**
 * The iOS [Download] (capability `receiving-photos`): a `URLSession` (Wi-Fi *and* cellular, non-discretionary) which on
 * every shipped binary is a **background** session that keeps downloading while the app is suspended and relaunches it
 * on completion. The binding is fixed by the compilation target — see [transferSessionConfiguration] for what
 * `iosSimulatorArm64` gets instead, and for the properties a run on that target does **not** evidence.
 *
 * Thin since phase 11f: it reports a finished body's facts and its temporary file to the registered handlers, which
 * judge and move it before the callback returns — the port's `onFinished` is inline for that reason. The queue, the
 * window, the tag codec and the staging path are the download feature's.
 *
 * **It never invalidates the session.** Cancellation is [cancelAll], task by task; the session outlives every leave.
 * If the *system* invalidates it, the handlers are told and the next [start] builds a fresh one — creating a task on an
 * invalidated session raises an `NSException` Kotlin/Native cannot catch.
 */
@OptIn(ExperimentalForeignApi::class)
class IosDownload(private val log: Logger = Logger.withTag("Download")) : Download {

    private val handlers = AtomicReference<DownloadHandlers?>(null)

    /** The live session, or `null` until one is wanted — or after the system invalidated the last one. */
    private val current = AtomicReference<NSURLSession?>(null)

    private val delegate = Delegate(this)

    override fun listen(handlers: DownloadHandlers) {
        this.handlers.store(handlers)
    }

    /**
     * The session, built when first wanted: on the first transfer, or on a `handleEventsForBackgroundURLSession`
     * relaunch, where it must exist for the OS to deliver the pending completions to its delegate. Built from
     * [transferSessionConfiguration] — background on every shipped binary, default on `iosSimulatorArm64` — whose
     * policy (Wi-Fi *and* cellular, no discretionary deferral, relaunch on completion) is declared there.
     */
    private fun session(): NSURLSession {
        current.load()?.let { return it }
        val built = NSURLSession.sessionWithConfiguration(
            transferSessionConfiguration(DOWNLOAD_SESSION_ID),
            delegate,
            null as NSOperationQueue?,
        )
        return if (current.compareAndSet(null, built)) built else checkNotNull(current.load())
    }

    override fun start(url: String, tag: String): StartResult {
        val nsUrl = NSURL.URLWithString(url) ?: return StartResult.NotStarted
        val task = session().downloadTaskWithURL(nsUrl)
        task.taskDescription = tag
        task.resume()
        return StartResult.Started
    }

    /** Cancels every task the session holds — ones a relaunched process inherited included — and returns once done. */
    override suspend fun cancelAll() = log.invocation("download.cancelAll") {
        val tasks = suspendCancellableCoroutine { cont ->
            session().getAllTasksWithCompletionHandler { all ->
                objcBoundary(log, "download.cancelAll.tasks") {
                    cont.resume(all?.mapNotNull { it as? NSURLSessionTask }.orEmpty())
                }
            }
        }
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
            SessionCompletion(completion).complete()
        } else {
            registered.onBackgroundEvents(SessionCompletion(completion))
        }
        session()
        Unit
    }

    private fun onFinished(task: NSURLSessionDownloadTask, location: NSURL) {
        val tag = task.taskDescription ?: return
        val temp = location.path ?: return
        handlers.load()?.onFinished?.invoke(tag, outcomeOf(task, temp), temp)
            ?: log.e { "download $tag finished before the composition listened — its bytes are downloaded again later" }
    }

    /**
     * The transfer's facts, read off the response and the file on disk. `expectedContentLength` is
     * `NSURLResponseUnknownLength` (-1) when the server sent no `Content-Length`, passed through verbatim: "unknown" is
     * a distinct answer from "zero" to the code that judges it.
     */
    private fun outcomeOf(task: NSURLSessionDownloadTask, path: String): TransferOutcome {
        val http = task.response as? NSHTTPURLResponse
        val received = checkedObjCValue("attributesOfItemAtPath") {
            NSFileManager.defaultManager.attributesOfItemAtPath(path, error = it)
        }.onFailure { log.w(it) { "the finished download's size is unreadable — judged as 0 bytes" } }
            .getOrNull()
            ?.get(NSFileSize) as? NSNumber
        return TransferOutcome(
            statusCode = http?.statusCode?.toInt(),
            expectedBytes = task.response?.expectedContentLength ?: -1L,
            receivedBytes = received?.longLongValue ?: 0L,
        )
    }

    private fun onComplete(task: NSURLSessionTask, error: NSError?) {
        val tag = task.taskDescription ?: return
        handlers.load()?.onCompleted?.invoke(tag, error?.localizedDescription)
    }

    /** The system invalidated [session] (we never do): forget it, so the next transfer builds a fresh one. */
    private fun onInvalidated(session: NSURLSession) {
        current.compareAndSet(session, null)
        handlers.load()?.onInvalidated?.invoke()
    }

    private fun onEventsFinished() {
        handlers.load()?.onEventsDrained?.invoke()
    }

    /**
     * The Obj-C download delegate — a NON-inner nested class (the proven codegen-safe shape for an Obj-C protocol
     * implementer) holding a back-reference to the [download] it forwards to.
     */
    private class Delegate(private val download: IosDownload) : NSObject(), NSURLSessionDownloadDelegateProtocol {
        // PLATFORM ENTRY POINTS (spec `privacy-security`): each records that it was called before doing anything. The
        // per-task callbacks log at DEBUG — once per photo, and at INFO a 200-photo event would flush the crash
        // reporter's bounded breadcrumb window and roll the size-capped device log before anyone read it.
        @PlatformEntry
        override fun URLSession(
            session: NSURLSession,
            downloadTask: NSURLSessionDownloadTask,
            didFinishDownloadingToURL: NSURL,
        ) = objcBoundary(download.log, "download.didFinishDownloading") {
            download.log.invocation("download.didFinishDownloading", severity = Severity.Debug) {
                download.onFinished(downloadTask, didFinishDownloadingToURL)
            }
        }

        @PlatformEntry
        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            didCompleteWithError: NSError?,
        ) = objcBoundary(download.log, "download.didComplete") {
            download.log.invocation(
                "download.didComplete",
                // Domain and code, not just `localizedDescription`: iOS renders NSURLErrorUnknown as the literal
                // "unknown error", which names nothing an operator can act on or search for.
                params = "error=${didCompleteWithError?.let { "${it.domain}/${it.code}: ${it.localizedDescription}" } ?: "«none»"}",
                severity = Severity.Debug,
            ) {
                download.onComplete(task, didCompleteWithError)
            }
        }

        /** The session died and was **not** killed by us (we never invalidate). */
        @PlatformEntry
        override fun URLSession(session: NSURLSession, didBecomeInvalidWithError: NSError?) =
            objcBoundary(download.log, "download.didBecomeInvalid") {
                download.log.w { "background session invalidated by the system: ${didBecomeInvalidWithError?.localizedDescription}" }
                download.onInvalidated(session)
            }

        // Session-level, not per-task: INFO.
        @PlatformEntry
        override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) =
            objcBoundary(download.log, "download.didFinishEvents") {
                download.log.invocation("download.didFinishEvents") { download.onEventsFinished() }
            }
    }
}
