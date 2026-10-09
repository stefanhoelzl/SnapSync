package app.snapsync.ios.urlsession

import app.snapsync.logging.invocation
import app.snapsync.model.PlatformEntry
import app.snapsync.objc.objcBoundary
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * The operating-system boundary of [IosUrlSessionUploadPlatform]: the background `URLSession` — brought up, given
 * transfers, asked for its live ones — what it delivers back (a transfer's end, the drain of a relaunch's events), and
 * the release of the relaunch's completion handler on the main thread UIKit requires. Nothing else, so what a phone's
 * relaunch delivered, recorded there, replays on every build (`docs/architecture.md`, "Hosts CI cannot reach are recorded
 * at the operating-system boundary").
 */
internal interface UploadSessionApi : HandlerRelease {

    /** Brings the session [identifier] up — re-attaching to its transfers — delivering what it reports to [events]. */
    fun open(identifier: String, events: SessionEvents): UploadSession

    /** Releases a relaunch's completion [handler], on the thread the platform requires. */
    override fun release(handler: () -> Unit)
}

/** What a session reports back, each on a thread the session chooses. */
internal class SessionEvents(
    /** [task] ended, with the response's [statusCode] (`0` without an HTTP response) and the transport [error]. */
    val completed: (task: TransferTask, statusCode: Long, error: NSError?) -> Unit,
    /** The session delivered every event it held for a relaunch. */
    val drained: () -> Unit,
)

/** One brought-up session. */
internal interface UploadSession {
    /** Starts a transfer of [file] to [request], tagged [tag]. */
    fun upload(request: NSURLRequest, file: String, tag: String)

    /** The session's transfers still running. */
    suspend fun tasks(): List<TransferTask>
}

/** One of the session's transfers, as far as the uploader reads it. */
internal interface TransferTask {
    /** The ledger key it was created with — the one field present across its lifecycle. */
    val tag: String?

    /** The path it was sent to. */
    val path: String?

    fun cancel()
}

/** The real one: `NSURLSession` under the configuration the compilation target fixes ([transferSessionConfiguration]). */
@OptIn(ExperimentalForeignApi::class)
internal class SystemUploadSessionApi(private val log: Logger) : UploadSessionApi {

    override fun open(identifier: String, events: SessionEvents): UploadSession {
        val session = NSURLSession.sessionWithConfiguration(
            transferSessionConfiguration(identifier),
            delegate = SessionDelegate(events),
            delegateQueue = null,
        )
        return SystemUploadSession(session, log)
    }

    override fun release(handler: () -> Unit) = MainThreadRelease.release(handler)
}

@OptIn(ExperimentalForeignApi::class)
private class SystemUploadSession(private val session: NSURLSession, private val log: Logger) : UploadSession {

    override fun upload(request: NSURLRequest, file: String, tag: String) {
        val task = session.uploadTaskWithRequest(request, fromFile = NSURL.fileURLWithPath(file))
        task.taskDescription = tag
        task.resume()
    }

    override suspend fun tasks(): List<TransferTask> = suspendCancellableCoroutine { cont ->
        session.getAllTasksWithCompletionHandler { tasks ->
            objcBoundary(log, "liveTasks.completion") {
                cont.resume(tasks?.mapNotNull { (it as? NSURLSessionTask)?.let(::SystemTransferTask) }.orEmpty())
            }
        }
    }
}

private class SystemTransferTask(private val task: NSURLSessionTask) : TransferTask {
    override val tag: String? get() = task.taskDescription
    override val path: String? get() = task.originalRequest?.URL?.path

    override fun cancel() = task.cancel()
}

/**
 * The `NSURLSession` background-session delegate — a plain `NSObject` (kept separate from the uploader because a class
 * implementing a Kotlin interface cannot also subclass an ObjC type). It only maps the two callbacks the uploader needs
 * into [SessionEvents].
 */
@OptIn(ExperimentalForeignApi::class)
private class SessionDelegate(
    private val events: SessionEvents,
    private val log: Logger = Logger.withTag("urlSessionUpload"),
) : NSObject(), NSURLSessionTaskDelegateProtocol {

    // PLATFORM ENTRY POINT. DEBUG, not INFO: one per uploaded photo, so at INFO a large event would flush the bounded
    // breadcrumb window and roll the device log.
    @PlatformEntry
    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) =
        objcBoundary(log, "upload.didComplete") {
            log.invocation(
                "upload.didComplete",
                params = "error=${didCompleteWithError?.localizedDescription ?: "«none»"}",
                severity = Severity.Debug,
            ) {
                events.completed(
                    SystemTransferTask(task),
                    (task.response as? NSHTTPURLResponse)?.statusCode ?: 0L,
                    didCompleteWithError,
                )
            }
        }

    // Session-level, once per OS re-attach: INFO.
    @PlatformEntry
    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) =
        objcBoundary(log, "upload.didFinishEvents") { log.invocation("upload.didFinishEvents") { events.drained() } }
}
