@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.ios.urlsession

import app.snapsync.ios.upload.uploadUrlRequest
import app.snapsync.logging.invocation
import app.snapsync.model.ChangeOutcome
import app.snapsync.model.PlatformEntry
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadError
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadJobState
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadSourceKind
import app.snapsync.model.UploadTarget
import app.snapsync.objc.objcBoundary
import app.snapsync.ports.Upload
import app.snapsync.ports.UploadHandlers
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.darwin.NSObject
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume

/**
 * The app's uploader (every iOS version): the [Upload] port over a **background `URLSession`** — the OS-owned durable
 * queue the PhotoKit tier gets for free, reimplemented in the app process. (The session's binding is fixed per
 * compilation target; see [transferSessionConfiguration].)
 *
 * Thin since phase 11f. It uploads from a **file** ([accepts] is [UploadSourceKind.FILE]): the upload services export
 * the resource with the photo library's `export`, hand the file here, and delete it when the transfer ends. It reports
 * a transfer's end the moment iOS delivers it, through [UploadHandlers.onFinished], whose handler records it inline;
 * the in-flight limit is its own ([cap] live tasks), answered [UploadCreateOutcome.LIMIT_EXCEEDED]; it offers no free
 * retry and holds nothing to acknowledge.
 *
 * Correctness is **at-least-once**: keys are deterministic and the edge PUT is idempotent, so a re-send overwrites the
 * same object. The ledger is the only durable state; the `URLSession` task list is a transient executor, tagged with
 * the ledger key via `taskDescription`. Delegate callbacks arrive on the session's delegate queue (via
 * [SessionDelegate], a separate `NSObject` — a Kotlin-interface class cannot also be an ObjC supertype).
 *
 * **What is tested and what is not.** How a delivered completion reads as a job state lives in `UrlSessionOutcome.kt`
 * and is exercised by `UrlSessionOutcomeTest`; the port's promises by `UploadContract`, live on the simulator app.
 */
@OptIn(ExperimentalForeignApi::class)
class IosUrlSessionUploadPlatform(
    private val log: Logger,
    sessionIdentifier: String,
    private val cap: Int = 4,
) : Upload {

    override val accepts: UploadSourceKind = UploadSourceKind.FILE

    private val handlers = AtomicReference<UploadHandlers?>(null)

    override fun listen(handlers: UploadHandlers) {
        this.handlers.store(handlers)
    }

    private val delegate = SessionDelegate(
        onComplete = { task, status, error -> finished(task, status, error) },
        onEventsFinished = { handlers.load()?.onEventsDrained?.invoke() },
    )

    private val sessionId = sessionIdentifier

    /**
     * On every shipped binary a **background** session, so transfers survive suspension (`background-upload`). The
     * binding is fixed by the **compilation target** — see [transferSessionConfiguration], the single place this file
     * and the download adapter both resolve it, where the platform facts and their ⏰ expiry live.
     *
     * Lazy, and brought up by [handleEvents] on a background relaunch: touching it re-attaches the session by its
     * identifier, so the OS delivers the completions of transfers that finished while the app was suspended.
     */
    private val session: NSURLSession by lazy {
        NSURLSession.sessionWithConfiguration(
            transferSessionConfiguration(sessionId),
            delegate = delegate,
            delegateQueue = null,
        )
    }

    /**
     * The operating system relaunched (or woke) the app to deliver this session's events. The handler is handed over
     * FIRST, then the session is brought up — so every event it then delivers, and the drain report after them, land in
     * that handler's window (adopt, then create).
     */
    fun handleEvents(completion: () -> Unit) = log.invocation("upload.handleEvents") {
        val registered = handlers.load()
        if (registered == null) {
            // Never silent: without handlers there is no one to hold the handler; answering it at once costs nothing.
            log.e { "upload session events arrived before the composition listened — released at once" }
            SessionCompletion(completion).complete()
        } else {
            registered.onBackgroundEvents(SessionCompletion(completion))
        }
        session
        Unit
    }

    override suspend fun create(source: UploadSource, target: UploadTarget, tag: String): UploadCreateOutcome =
        log.invocation("urlSession.create", params = "tag=$tag", result = { "$it" }) {
            val file = (source as? UploadSource.File)?.path ?: run {
                log.w { "create: this uploader sends a file, not a resource handle — not creating" }
                return@invocation UploadCreateOutcome.FAILED
            }
            // The cap is measured against the SESSION's live tasks, not an in-process count — the OS owns the transfers
            // and they outlive us, so this is the only count that binds across a relaunch.
            if (liveTasks().size >= cap) return@invocation UploadCreateOutcome.LIMIT_EXCEEDED
            val url = NSURL.URLWithString(target.url) ?: return@invocation UploadCreateOutcome.FAILED
            val request = uploadUrlRequest(url, target)
            val task = session.uploadTaskWithRequest(request, fromFile = NSURL.fileURLWithPath(file))
            task.taskDescription = tag // the ledger key — the only field present across the lifecycle
            task.resume()
            UploadCreateOutcome.CREATED
        }

    /** Only in-flight transfers are known here: a terminal one is reported as it ends, and none is offered a retry. */
    override suspend fun jobs(set: UploadJobSet): List<UploadJob> = when (set) {
        UploadJobSet.IN_FLIGHT -> liveTasks().map { task -> jobOf(task, UploadJobState.PENDING, NO_ERROR) }
        UploadJobSet.RETRY_OFFERED, UploadJobSet.TERMINAL -> emptyList()
    }

    override suspend fun retry(job: UploadJob, target: UploadTarget): ChangeOutcome =
        ChangeOutcome.Refused(null, "a URLSession upload has no free retry; a failure is re-created")

    /** Nothing to acknowledge: a transfer's end is reported once, as it happens. */
    override suspend fun acknowledge(job: UploadJob): ChangeOutcome = ChangeOutcome.Applied

    override suspend fun cancel(job: UploadJob): ChangeOutcome {
        val task = job.handle as? NSURLSessionTask ?: return ChangeOutcome.Refused(null, "not a URLSession task")
        task.cancel()
        return ChangeOutcome.Applied
    }

    /**
     * iOS delivered [task]'s end — exactly once (`URLSessionTask.State.completed`: *"the task's delegate receives no
     * further callbacks"*). Handed to the registered handler synchronously, which records it before this returns. A
     * task with no description maps to no ledger key and is reported, never dropped in silence.
     */
    private fun finished(task: NSURLSessionTask, statusCode: Long, error: NSError?) {
        when (val outcome = classifyUrlSessionCompletion(task.taskDescription, statusCode, error)) {
            TaskCompletion.NoLedgerKey -> log.w { "upload task carried no description — nothing to record" }
            is TaskCompletion.Record -> {
                val state = if (outcome.success) UploadJobState.SUCCEEDED else UploadJobState.FAILED
                val registered = handlers.load()
                if (registered == null) {
                    log.e { "upload ${outcome.key} ended before the composition listened — its outcome is not recorded" }
                } else {
                    registered.onFinished(jobOf(task, state, outcome.error))
                }
            }
        }
    }

    private fun jobOf(task: NSURLSessionTask, state: UploadJobState, error: UploadError?) = UploadJob(
        handle = task,
        tag = task.taskDescription,
        destinationPath = task.originalRequest?.URL?.path,
        contentType = null,
        state = state,
        error = error,
        source = null,
    )

    private suspend fun liveTasks(): List<NSURLSessionTask> = suspendCancellableCoroutine { cont ->
        session.getAllTasksWithCompletionHandler { tasks ->
            objcBoundary(log, "liveTasks.completion") {
                cont.resume(tasks?.mapNotNull { it as? NSURLSessionTask }.orEmpty())
            }
        }
    }
}

/**
 * The `NSURLSession` background-session delegate — a plain `NSObject` (kept separate from [IosUrlSessionUploadPlatform]
 * because a class implementing a Kotlin interface cannot also subclass an ObjC type). It only maps the two callbacks the
 * uploader needs into plain Kotlin lambdas.
 */
@OptIn(ExperimentalForeignApi::class)
private class SessionDelegate(
    private val onComplete: (task: NSURLSessionTask, statusCode: Long, error: NSError?) -> Unit,
    private val onEventsFinished: () -> Unit,
    private val log: Logger = Logger.withTag("urlSessionUpload"),
) : NSObject(), NSURLSessionTaskDelegateProtocol {

    // PLATFORM ENTRY POINT (spec `privacy-security`). DEBUG, not INFO: one per uploaded photo, so at INFO a large event
    // would flush the bounded breadcrumb window and roll the device log.
    @PlatformEntry
    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) =
        objcBoundary(log, "upload.didComplete") {
            log.invocation(
                "upload.didComplete",
                params = "error=${didCompleteWithError?.localizedDescription ?: "«none»"}",
                severity = Severity.Debug,
            ) {
                onComplete(task, (task.response as? NSHTTPURLResponse)?.statusCode ?: 0L, didCompleteWithError)
            }
        }

    // Session-level, once per OS re-attach: INFO.
    @PlatformEntry
    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) =
        objcBoundary(log, "upload.didFinishEvents") { log.invocation("upload.didFinishEvents") { onEventsFinished() } }
}

/** An in-flight task has no error yet. */
private val NO_ERROR: UploadError? = null
