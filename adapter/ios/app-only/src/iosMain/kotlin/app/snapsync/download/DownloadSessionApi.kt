package app.snapsync.download

import app.snapsync.ios.urlsession.HandlerRelease
import app.snapsync.ios.urlsession.MainThreadRelease
import app.snapsync.ios.urlsession.transferSessionConfiguration
import app.snapsync.logging.invocation
import app.snapsync.model.PlatformEntry
import app.snapsync.model.TransferOutcome
import app.snapsync.objc.checkedObjCValue
import app.snapsync.objc.objcBoundary
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
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * The operating-system boundary of [IosDownload]: the background `URLSession` — brought up, given transfers, asked for
 * its live ones — what it delivers back (a body, a transfer's end, its own invalidation, the drain of a relaunch's
 * events), and the release of the relaunch's completion handler on the main thread UIKit requires. Nothing else, so
 * what a phone's relaunch delivered, recorded there, replays on every build (`docs/architecture.md`, "Hosts CI cannot
 * reach are recorded at the operating-system boundary").
 */
internal interface DownloadSessionApi : HandlerRelease {

    /** Brings the session [identifier] up — re-attaching to its transfers — delivering what it reports to [events]. */
    fun open(identifier: String, events: DownloadSessionEvents): DownloadSession

    /** Releases a relaunch's completion [handler], on the thread the platform requires. */
    override fun release(handler: () -> Unit)
}

/** What a session reports back, each on a thread the session chooses. */
internal class DownloadSessionEvents(
    /** [task] received its body into the temporary file [path], which is gone once this returns; [facts] as read. */
    val finished: (task: DownloadTask, facts: TransferOutcome, path: String) -> Unit,
    /** [task] ended, with the transport [error]. */
    val completed: (task: DownloadTask, error: NSError?) -> Unit,
    /** The system invalidated the session (it is never invalidated here). */
    val invalidated: (error: NSError?) -> Unit,
    /** The session delivered every event it held for a relaunch. */
    val drained: () -> Unit,
)

/** One brought-up session. */
internal interface DownloadSession {
    /** Starts fetching [request], tagged [tag]. */
    fun download(request: NSURLRequest, tag: String)

    /** The session's transfers still running. */
    suspend fun tasks(): List<DownloadTask>
}

/** One of the session's transfers, as far as the downloader reads it. */
internal interface DownloadTask {
    /** The tag it was started with — the one field present across its lifecycle. */
    val tag: String?

    fun cancel()
}

/** The real one: `NSURLSession` under the configuration the compilation target fixes ([transferSessionConfiguration]). */
@OptIn(ExperimentalForeignApi::class)
internal class SystemDownloadSessionApi(private val log: Logger) : DownloadSessionApi {

    override fun open(identifier: String, events: DownloadSessionEvents): DownloadSession {
        val session = NSURLSession.sessionWithConfiguration(
            transferSessionConfiguration(identifier),
            DownloadDelegate(events, log),
            null as NSOperationQueue?,
        )
        return SystemDownloadSession(session, log)
    }

    override fun release(handler: () -> Unit) = MainThreadRelease.release(handler)
}

@OptIn(ExperimentalForeignApi::class)
private class SystemDownloadSession(private val session: NSURLSession, private val log: Logger) : DownloadSession {

    override fun download(request: NSURLRequest, tag: String) {
        val task = session.downloadTaskWithRequest(request)
        task.taskDescription = tag
        task.resume()
    }

    override suspend fun tasks(): List<DownloadTask> = suspendCancellableCoroutine { cont ->
        session.getAllTasksWithCompletionHandler { all ->
            objcBoundary(log, "download.cancelAll.tasks") {
                cont.resume(all?.mapNotNull { (it as? NSURLSessionTask)?.let(::SystemDownloadTask) }.orEmpty())
            }
        }
    }
}

private class SystemDownloadTask(private val task: NSURLSessionTask) : DownloadTask {
    override val tag: String? get() = task.taskDescription

    override fun cancel() = task.cancel()
}

/**
 * The transfer's facts, read off the response and the file on disk. `expectedContentLength` is
 * `NSURLResponseUnknownLength` (-1) when the server sent no `Content-Length`, passed through verbatim: "unknown" is a
 * distinct answer from "zero" to the code that judges it.
 */
@OptIn(ExperimentalForeignApi::class)
private fun outcomeOf(task: NSURLSessionDownloadTask, path: String, log: Logger): TransferOutcome {
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

/**
 * The Obj-C download delegate — a plain `NSObject` (the proven codegen-safe shape for an Obj-C protocol implementer)
 * mapping the session's callbacks into [DownloadSessionEvents].
 */
@OptIn(ExperimentalForeignApi::class)
private class DownloadDelegate(
    private val events: DownloadSessionEvents,
    private val log: Logger,
) : NSObject(), NSURLSessionDownloadDelegateProtocol {
    // PLATFORM ENTRY POINTS: each records that it was called before doing anything. The
    // per-task callbacks log at DEBUG — once per photo, and at INFO a 200-photo event would flush the crash
    // reporter's bounded breadcrumb window and roll the size-capped device log before anyone read it.
    @PlatformEntry
    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didFinishDownloadingToURL: NSURL,
    ) = objcBoundary(log, "download.didFinishDownloading") {
        log.invocation("download.didFinishDownloading", severity = Severity.Debug) {
            val path = didFinishDownloadingToURL.path ?: return@invocation
            events.finished(SystemDownloadTask(downloadTask), outcomeOf(downloadTask, path, log), path)
        }
    }

    @PlatformEntry
    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) = objcBoundary(log, "download.didComplete") {
        log.invocation(
            "download.didComplete",
            // Domain and code, not just `localizedDescription`: iOS renders NSURLErrorUnknown as the literal
            // "unknown error", which names nothing an operator can act on or search for.
            params = "error=${didCompleteWithError?.let { "${it.domain}/${it.code}: ${it.localizedDescription}" } ?: "«none»"}",
            severity = Severity.Debug,
        ) {
            events.completed(SystemDownloadTask(task), didCompleteWithError)
        }
    }

    /** The session died and was **not** killed by us (we never invalidate). */
    @PlatformEntry
    override fun URLSession(session: NSURLSession, didBecomeInvalidWithError: NSError?) =
        objcBoundary(log, "download.didBecomeInvalid") {
            log.w { "background session invalidated by the system: ${didBecomeInvalidWithError?.localizedDescription}" }
            events.invalidated(didBecomeInvalidWithError)
        }

    // Session-level, not per-task: INFO.
    @PlatformEntry
    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) =
        objcBoundary(log, "download.didFinishEvents") {
            log.invocation("download.didFinishEvents") { events.drained() }
        }
}
