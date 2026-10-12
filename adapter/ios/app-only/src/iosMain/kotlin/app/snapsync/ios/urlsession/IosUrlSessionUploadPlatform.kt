@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.ios.urlsession

import app.snapsync.ios.upload.uploadUrlRequest
import app.snapsync.logging.invocation
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.Transfer
import app.snapsync.ports.TransferHandlers
import app.snapsync.ports.TransferSession
import app.snapsync.ports.TransferSource
import app.snapsync.ports.UploadTarget
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSError
import platform.Foundation.NSURL
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The app's uploader (every iOS version): the [TransferSession] port over a **background `URLSession`** — the OS-owned durable
 * queue the PhotoKit tier gets for free, reimplemented in the app process. (The session's binding is fixed per
 * compilation target; see [transferSessionConfiguration].)
 *
 * Thin. It uploads from a **file** ([takesResources] is false): the upload services export the resource with the photo
 * library's `export`, hand the file here, and delete it when the transfer ends. It reports a transfer's end the moment
 * iOS delivers it, through [TransferHandlers.onFinished], whose handler records it inline; the in-flight limit is its
 * own ([cap] live tasks), answered [CreateOutcome.LIMIT_EXCEEDED].
 *
 * Correctness is **at-least-once**: keys are deterministic and the edge PUT is idempotent, so a re-send overwrites the
 * same object. The ledger is the only durable state; the `URLSession` task list is a transient executor, tagged with
 * the ledger key via `taskDescription`. Delegate callbacks arrive on the session's delegate queue, through
 * [UploadSessionApi] — the operating-system boundary, which a phone's relaunch is recorded at and replayed through.
 *
 * **What is tested and what is not.** How a delivered completion reads as a job state lives in `UrlSessionOutcome.kt`
 * and is exercised by `UrlSessionOutcomeTest`; the port's promises by `TransferSessionContract`, live on the simulator
 * app.
 */
@OptIn(ExperimentalForeignApi::class)
class IosUrlSessionUploadPlatform internal constructor(
    private val log: Logger,
    sessionIdentifier: String,
    private val cap: Int,
    // The operating-system boundary; production always passes the real one.
    private val api: UploadSessionApi,
) : TransferSession {

    constructor(log: Logger, sessionIdentifier: String, cap: Int = 4) :
        this(log, sessionIdentifier, cap, SystemUploadSessionApi(Logger.withTag("urlSessionUpload")))

    override val takesResources: Boolean = false

    private val handlers = AtomicReference<TransferHandlers?>(null)

    override fun listen(handlers: TransferHandlers) {
        this.handlers.store(handlers)
    }

    private val events = SessionEvents(
        completed = { task, status, error -> finished(task, status, error) },
        drained = { handlers.load()?.onEventsDrained?.invoke() },
    )

    private val sessionId = sessionIdentifier

    /**
     * On every shipped binary a **background** session, so transfers survive suspension. The
     * binding is fixed by the **compilation target** — see [transferSessionConfiguration], the single place this file
     * and the download adapter both resolve it, where the platform facts and their ⏰ expiry live.
     *
     * Lazy, and brought up by [handleEvents] on a background relaunch: touching it re-attaches the session by its
     * identifier, so the OS delivers the completions of transfers that finished while the app was suspended.
     */
    private val session: UploadSession by lazy { api.open(sessionId, events) }

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
            SessionCompletion(completion, api).complete()
        } else {
            registered.onBackgroundEvents(SessionCompletion(completion, api))
        }
        session
        Unit
    }

    override suspend fun create(source: TransferSource, target: UploadTarget, tag: String): CreateOutcome =
        log.invocation("urlSession.create", params = "tag=$tag", result = { "$it" }) {
            val whole = source as? TransferSource.File ?: run {
                log.w { "create: this uploader sends a file, not a resource handle — not creating" }
                return@invocation CreateOutcome.FAILED
            }
            // The cap is measured against the SESSION's live tasks, not an in-process count — the OS owns the transfers
            // and they outlive us, so this is the only count that binds across a relaunch.
            if (session.tasks().size >= cap) return@invocation CreateOutcome.LIMIT_EXCEEDED
            val url = NSURL.URLWithString(target.url) ?: return@invocation CreateOutcome.FAILED
            // A resumed upload sends the file's rest, which a background session can only send as a file of its own.
            val file = if (whole.from <= 0) {
                whole.path
            } else {
                UploadSlices.cut(whole.path, whole.from, tag) ?: run {
                    log.w { "create: the rest of $tag from ${whole.from} could not be copied out — not creating" }
                    return@invocation CreateOutcome.FAILED
                }
            }
            // Tagged with the ledger key — the only field present across the lifecycle. A file that cannot be read is no
            // transfer: refused now, never one that fails later.
            if (!session.upload(uploadUrlRequest(url, target), file, tag)) {
                log.w { "create: $file cannot be read — not creating" }
                if (whole.from > 0) UploadSlices.release(tag)
                return@invocation CreateOutcome.FAILED
            }
            CreateOutcome.CREATED
        }

    override suspend fun live(): List<Transfer> = session.tasks().map { Transfer(it, it.tag) }

    override suspend fun cancel(transfer: Transfer) {
        (transfer.handle as? TransferTask)?.cancel()
    }

    /**
     * iOS delivered [task]'s end — exactly once (`URLSessionTask.State.completed`: *"the task's delegate receives no
     * further callbacks"*). Handed to the registered handler synchronously, which records it before this returns. A
     * task with no description maps to no ledger key and is reported, never dropped in silence.
     */
    private fun finished(task: TransferTask, statusCode: Long, error: NSError?) {
        task.tag?.let(UploadSlices::release)
        when (val outcome = classifyUrlSessionCompletion(task.tag, statusCode, error)) {
            TaskCompletion.NoLedgerKey -> log.w { "upload task carried no description — nothing to record" }
            is TaskCompletion.Record -> {
                val registered = handlers.load()
                if (registered == null) {
                    log.e { "upload ${outcome.key} ended before the composition listened — its outcome is not recorded" }
                } else {
                    registered.onFinished(outcome.key, outcome.error)
                }
            }
        }
    }
}
