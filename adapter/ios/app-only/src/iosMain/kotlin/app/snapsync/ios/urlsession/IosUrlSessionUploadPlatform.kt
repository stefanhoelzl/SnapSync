package app.snapsync.ios.urlsession

import app.snapsync.model.SelectionPolicy
import app.snapsync.model.Resource
import app.snapsync.model.UploadError
import app.snapsync.model.UploadRequest
import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.model.LedgerState
import app.snapsync.ports.CreateResult
import app.snapsync.ports.Discovery
import app.snapsync.ports.LedgerStore
import app.snapsync.ports.PlatformUploadJob
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.logging.invocation
import app.snapsync.model.PlatformEntry
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.NSURLSessionUploadTask
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceManager
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * The app-driven (iOS 18–26.0) implementation of `:domain`'s [BackgroundTransfer] port, backed on every
 * shipped binary by a **background `URLSession`** — the OS-owned durable queue the PhotoKit tier gets for
 * free, reimplemented in the app process. (The session's binding is fixed per compilation target; see
 * [transferSessionConfiguration].) `UploadCycle` runs unchanged over it; only the job lifecycle
 * differs, mapped to `URLSession` semantics (see the change `add-url-session-upload`):
 *
 * - [createJob] stages the resource's bytes to a temp file (per open slot, bounded by [cap]) and
 *   starts a background `uploadTask(fromFile:)` tagged with the ledger key via `taskDescription`.
 * - [fetchRetryJobs] is always **empty** — this platform grants no OS single retry; failures come back
 *   through [fetchAckJobs] as retry-spent and `UploadCycle` recreates them.
 * - [fetchAckJobs] drains delivered `URLSession` completions AND reconciles precisely: a ledger
 *   `REQUESTED` key ([pendingKeys]) with no live task and no completion this round is surfaced as a
 *   terminal FAILED job, flipping the row `REQUESTED`→`FAILED` so a later full enumeration re-uploads
 *   it (replacing the PhotoKit tier's blanket `clearRequested`).
 * - [acknowledge] is local cleanup (drop the record, delete the staged temp file) — no OS call.
 *
 * Correctness is **at-least-once**: keys are deterministic and the edge PUT is idempotent, so a
 * re-send overwrites the same object. The ledger is the only durable state; the `URLSession` task list
 * is a transient executor reconciled by `taskDescription == key`. Delegate callbacks arrive on the
 * session's delegate queue (via [SessionDelegate], a separate `NSObject` — a Kotlin-interface class
 * cannot also be an ObjC supertype) while seam methods run on the cycle coroutine, so shared state
 * ([inFlight], [terminal]) is guarded by [lock].
 *
 * **What is tested and what is not.** The two decisions this tier makes — how a delivered task
 * completion maps to a ledger outcome, and which `REQUESTED` keys count as stranded — live in
 * `UrlSessionOutcome.kt` beside this file and are exercised by `UrlSessionOutcomeTest`. What remains
 * here is mechanism: the lock, the in-flight registry, byte staging, the orphan sweep, and the
 * session/delegate lifecycle. Those are device-verified and faked in the harness; their correctness is
 * concurrency and filesystem behaviour, which extraction does not make more provable.
 */
@OptIn(ExperimentalForeignApi::class)
class IosUrlSessionUploadPlatform(
    private val log: Logger,
    private val discovery: IosDiscovery,
    private val appGroup: String,
    sessionIdentifier: String,
    // The ledger, held directly. This adapter RECORDS: the party iOS tells that an upload terminated is
    // this delegate, iOS tells it exactly once, and a fact parked in memory for a later cycle to collect
    // does not survive the process. `markTerminal` is the guarded, non-suspending write that lets a
    // completion callback record before it returns (`sync-ledger`). Recording through the store rather
    // than a `LedgerWriter` is deliberate and narrow — see that spec's reader/writer split.
    private val ledger: LedgerStore,
    private val cap: Int = 4,
    // Fired after each task reaches a terminal state — the composition root wires this to the pump's
    // `onUploadCompleted` (a slot just freed → top up).
    private val onTerminal: () -> Unit,
    // Fired when the session reports every enqueued event delivered (the background-session relaunch
    // delegate callback) — the composition root wires this to the receipts' `drained()`.
    //
    // A constructor `val`, like `onTerminal` beside it, not the settable `var` it used to be. Two
    // reasons, and the second is the load-bearing one. It is set exactly once, by the one root that
    // builds this platform, so nothing needed the mutability. And the `var` form — a mutable field of
    // type `(() -> Unit)?` — is the shape a `:test:architecture` guard now confines to
    // `BackgroundEventsReceipts`, because that is the shape a stored OS completion handler takes. This
    // slot is NOT an OS handler, merely identical in type; allowlisting it would have put a non-handler
    // in a handler guard's exemption list and invited the next one. Cheaper to not be that shape.
    private val onEventsFinished: () -> Unit,
) : BackgroundTransfer {

    // `contentType` is the media type the task's own request was created with. It is carried because
    // `UploadCycle` rebuilds a retried job's `Resource` from the key alone, with empty metadata — so
    // whatever a surfaced job reports becomes the type the recreated upload is stored under. Reporting a
    // fixed placeholder here is not inert: it mistypes the object for the rest of its life (the PhotoKit
    // tier had exactly that defect, where every object that failed once was stored as
    // `application/octet-stream`). That tier recovers the value from the OS's stored request; this one
    // never hands the request away, so it simply keeps it.
    // No in-flight registry and no terminal list. Every fact this adapter needs is recoverable without
    // one: the staged file's path is a pure function of the key, the request's content type is a ledger
    // column, and the live tasks are the session's own (`getAllTasks`). The download transport reached the
    // same conclusion first — "the destination must be derivable from the description alone" — and the
    // kill-test in `module-architecture` asks for exactly this: after a relaunch every fact recoverable
    // through a port, keyed only by identifiers the external system persisted.
    //
    // It also fixes a cap that did not bind. `createJob` used to compare against an in-process count,
    // which is EMPTY after a relaunch while `nsurlsessiond` still holds live tasks — so a relaunch could
    // run twice the cap in parallel transfers.

    private val delegate = SessionDelegate(
        onComplete = ::recordTerminal,
        onEventsFinished = { onEventsFinished() },
    )

    private val sessionId = sessionIdentifier

    /**
     * On every shipped binary a **background** session, so transfers survive suspension
     * (`ios-url-session-upload`). The binding is fixed by the **compilation target**, not chosen here and
     * not read from the host: see [transferSessionConfiguration], which is the single place this file and
     * [app.snapsync.download.IosDownloadTransport] both resolve it, so the two cannot diverge.
     *
     * **That file, not this comment, is where the platform facts live.** This spot has carried a false
     * claim twice — first that a simulator cannot run background sessions (unproven when written), then
     * that it can (a probe that aimed at a closed port and read `NSURLErrorUnknown` as a refusal). Both
     * survived because the claim lived in a comment beside the code it justified, where nothing
     * re-measured it. It is stated once now, on the seam, with its evidence and its ⏰ expiry trigger, and
     * two gates hold the bindings in place (`architecture-guards`, "The transport-binding gate").
     *
     * ⚠️ [reattach] is structurally inert wherever the binding is `default`: `getAllTasks` can never find a
     * prior process's task, because no transfer outlives the process there.
     *
     * Decision record: `changes/bind-transport-session-by-target` (superseding
     * `changes/archive/2026-08-25-correct-simulator-background-session-claims` D1 and
     * `changes/archive/2026-08-09-delete-simulator-session-downgrade` D1; neither archive is edited).
     */
    private val session: NSURLSession by lazy {
        NSURLSession.sessionWithConfiguration(
            transferSessionConfiguration(sessionId),
            delegate = delegate,
            delegateQueue = null,
        )
    }

    private val stagingDir: NSURL? by lazy {
        val container = NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(appGroup)
        container?.URLByAppendingPathComponent("upload-staging", isDirectory = true)?.also { dir ->
            NSFileManager.defaultManager.createDirectoryAtURL(dir, withIntermediateDirectories = true, attributes = null, error = null)
        }
    }

    override suspend fun discoverResources(sinceToken: ByteArray?, policy: SelectionPolicy): Discovery =
        log.invocation("platform.discoverResources", result = { "${it.candidates.size} candidate(s)" }) {
            discovery.discover(sinceToken, policy)
        }

    // Shared with the other tier: the id-scoped resolve lives in `IosDiscovery` beside the walk, because
    // both are PhotoKit fetches and only the job lifecycle differs between the tiers.
    override suspend fun resourcesFor(keys: Set<String>): List<Resource> =
        log.invocation("platform.resourcesFor", params = "${keys.size} key(s)", result = { "${it.size} resource(s)" }) {
            discovery.resourcesFor(keys)
        }

    // No OS-sponsored free retry on this platform: failures return via fetchAckJobs and are recreated.
    override suspend fun fetchRetryJobs(): List<PlatformUploadJob> =
        log.invocation("platform.fetchRetryJobs", result = { "${it.size} job(s)" }) {
            emptyList<PlatformUploadJob>()
        }

    override suspend fun createJob(request: UploadRequest, resource: Resource): CreateResult =
        log.invocation("platform.createJob", params = "key=${request.resource.filename}", result = { "$it" }) {
        val phResource = resource.data as? PHAssetResource ?: run {
            log.w { "createJob: resource payload is not a PHAssetResource — not creating" }
            return@invocation CreateResult.FAILED
        }
        // The cap is measured against the SESSION's live tasks, not an in-process count — the OS owns
        // the transfers and they outlive us, so this is the only count that binds across a relaunch.
        if (liveTaskKeys().size >= cap) return@invocation CreateResult.LIMIT_EXCEEDED

        val fileUrl = stageResource(phResource, resource.filename) ?: run {
            log.w { "createJob: staging failed for ${resource.filename} — not creating" }
            return@invocation CreateResult.FAILED
        }
        val url = NSURL.URLWithString(request.url) ?: run {
            deleteFile(fileUrl)
            log.w { "createJob: malformed destination URL — not creating" }
            return@invocation CreateResult.FAILED
        }
        val urlRequest = discovery.buildRequest(url, request)
        val task = session.uploadTaskWithRequest(urlRequest, fromFile = fileUrl)
        task.taskDescription = resource.filename // the ledger key — the only field present across the lifecycle
        task.resume()
        return@invocation CreateResult.CREATED
    }

    // Unused on this tier (fetchRetryJobs is always empty), implemented as cancel-and-recreate for the seam.
    override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) =
        log.invocation("platform.retryJob", params = "key=${job.key}") {
            cancelKey(job.key)
            val resource = job.data as? PHAssetResource ?: return@invocation
            createJob(request, Resource(job.key, "", job.contentType, emptyMap(), resource))
            Unit
        }

    /** The staged file for a key — a pure function of it, so no process has to remember the path. */
    private fun stagedFileFor(key: String): NSURL? = stagingDir?.URLByAppendingPathComponent(key)

    /**
     * Nothing crosses this seam on this tier.
     *
     * A completion is recorded into the ledger by the delegate the moment iOS delivers it, so there is no
     * terminal fact left to hand up; and a failure carries no live resource here, so there is nothing the
     * cycle could re-create in-cycle either — the engine re-uploads a `FAILED` key from a later discovery.
     * What this pass still owes is the **stranded reconciliation** below, which is bookkeeping about rows
     * rather than a job for the caller. `acknowledge` is gone with it: the staged file is deleted where
     * the transfer ends, which is also where it stops being usable.
     */
    override suspend fun drainTerminals(): List<PlatformUploadJob> =
        log.invocation("platform.drainTerminals", result = { "${it.size} job(s)" }) {
            reconcileStranded()
            emptyList<PlatformUploadJob>()
        }

    /**
     * A `REQUESTED` row whose task the session no longer holds was lost — the OS dropped the transfer, or
     * a force-quit cancelled it — and in both cases iOS delivers no completion at all, so nothing else
     * will ever move that row. Record it `FAILED` so a later enumeration re-uploads it.
     *
     * Two things this deliberately does NOT do.
     *
     * It does not consider anything but `REQUESTED` rows. It used to be handed the whole non-settled
     * backlog, so every `FAILED` row was re-reported as newly stranded on every cycle until it completed:
     * a field log shows one key "stranded" twelve times inside a single process, seven within sixteen
     * seconds. And now that `UPLOADED` exists, that same wide read would hand a freshly-uploaded row to
     * this pass, which would write it back to `FAILED` — destroying the fact the delegate just made
     * durable.
     *
     * It does not ask storage whether the bytes landed. That check (`ios-url-session-upload` required it;
     * this adapter never implemented it) existed to compensate for a terminal outcome that was not
     * recorded durably. With the outcome recorded when iOS delivers it, what is left here genuinely did
     * not land, so a full per-device listing would buy a "no" — and a re-PUT is idempotent and cheaper.
     *
     * The write is the same guarded verb the delegate uses, so a row that reached `UPLOADED` between the
     * read below and the write is never clobbered: the candidates are read first, the two are not atomic,
     * and the guard — not the read — is what makes that safe.
     */
    private suspend fun reconcileStranded() {
        val stranded = strandedKeys(pending = ledger.requestedKeys(), live = liveTaskKeys())
        for (key in stranded) {
            if (ledger.markTerminal(key, LedgerState.FAILED)) {
                log.i { "reconcile: stranded REQUESTED $key (no live task) — recorded FAILED to re-upload" }
            } else {
                // Not silent: the row moved on under us, which is a different fact from "recorded".
                log.i { "reconcile: stranded $key settled underneath this pass — left as it stands" }
            }
        }
    }

    /**
     * Force the (lazy) background session to be adopted for this process — on a
     * `handleEventsForBackgroundURLSession` relaunch, this re-attaches the session by its identifier so
     * it re-delivers the completion callbacks for transfers finished while the app was suspended.
     */
    fun reattach() {
        session // touching the lazy val instantiates + adopts the background session
    }

    /**
     * Cancel all in-flight transfers + clear staged files (on leave / disable / event switch).
     *
     * Asks the SESSION which tasks exist rather than a registry of our own, so it also cancels transfers
     * this process never started — the ones a relaunch inherited, which are exactly the ones a leave must
     * stop. Ledger rows are deliberately untouched: a cancelled task delivers no completion, so its
     * `REQUESTED` row is picked up by the stranded pass on the next cycle.
     */
    suspend fun cancelAll() {
        liveTasks().forEach { task ->
            task.cancel()
            task.taskDescription?.let { key -> stagedFileFor(key)?.let(::deleteFile) }
        }
    }

    private suspend fun cancelKey(key: String) {
        liveTasks().filter { it.taskDescription == key }.forEach { it.cancel() }
        stagedFileFor(key)?.let(::deleteFile)
    }

    /**
     * Delete staged temp files orphaned by a prior killed process — but NEVER a file still referenced
     * by a live background task (the OS may still be uploading from it across the relaunch), so only
     * files whose key is absent from the session's live task set are removed. Call once at startup.
     */
    suspend fun sweepStaging() {
        val dir = stagingDir ?: return
        val live = liveTaskKeys()
        val files = NSFileManager.defaultManager.contentsOfDirectoryAtURL(
            dir, includingPropertiesForKeys = null, options = 0u, error = null,
        ) ?: return
        files.forEach { entry ->
            val fileUrl = entry as? NSURL ?: return@forEach
            val name = fileUrl.lastPathComponent
            if (name != null && name !in live) deleteFile(fileUrl)
        }
    }

    /**
     * Called from the delegate queue with the task's key + outcome: **record it durably, right here,
     * before returning.**
     *
     * This is the whole fix. iOS delivers a background-`URLSession` completion exactly once —
     * `URLSessionTask.State.completed` is documented as *"the task has completed (without being canceled),
     * and the task's delegate receives no further callbacks"*, and `handleEventsForBackgroundURLSession`
     * hands over only events still *waiting* to be processed. This used to append the outcome to an
     * in-memory list for a later `UploadCycle` to drain; the drain is gated on a single-flight cycle
     * measured in the field at 27 minutes, 65 minutes and 4h49m, so a process death in between lost the
     * fact for good. The row then still read `REQUESTED` with no live task, the next cycle called it
     * stranded, and bytes that had already landed were uploaded again — twice, in one field dump, 27h30m
     * before the ledger caught up. ⏰ Re-check the once-only premise at the next iOS major.
     *
     * Synchronous, not scheduled. Once this returns the process's continued runtime is not ours to
     * assume — the app is reliably running *inside* the callback, and a held background-session receipt
     * is released on its own deadline whether or not the work it was waiting for happened (SNAPSYNC-16
     * shows one doing exactly that; `BackgroundEventsReceipts` emits the line, and this file deliberately
     * does not reproduce it — that clause is pinned to its emitters).
     * [LedgerStore.markTerminal] is non-suspending for this reason.
     *
     * The staged file goes at the same moment: the transfer is over, so it can never be uploaded from
     * again, and the launch-time sweep covers whatever a killed process leaves behind.
     */
    private fun recordTerminal(key: String, success: Boolean, error: UploadError?) {
        val state = if (success) LedgerState.UPLOADED else LedgerState.FAILED
        val applied = ledger.markTerminal(key, state)
        stagedFileFor(key)?.let(::deleteFile)
        if (applied) {
            log.i { "task terminal: $key -> $state" }
        } else {
            // Never silent (`module-architecture`, "Absence is never silent"): the guard matched no row,
            // so this key was not REQUESTED — already settled, or pruned. That is a different fact from
            // "recorded", and it is the only line that would show a completion arriving for a row we no
            // longer hold.
            log.w { "task terminal: $key -> $state applied to NO row (not REQUESTED — already settled, or pruned)" }
        }
        if (!success) log.i { "task terminal: $key failed with ${error ?: "«unspecified»"}" }
        onTerminal()
    }

    private suspend fun liveTasks(): List<NSURLSessionTask> = suspendCancellableCoroutine { cont ->
        session.getAllTasksWithCompletionHandler { tasks ->
            cont.resume(tasks?.mapNotNull { it as? NSURLSessionTask }.orEmpty())
        }
    }

    private suspend fun liveTaskKeys(): Set<String> = suspendCancellableCoroutine { cont ->
        session.getAllTasksWithCompletionHandler { tasks ->
            val keys = HashSet<String>()
            tasks?.forEach { (it as? NSURLSessionTask)?.taskDescription?.let(keys::add) }
            cont.resume(keys)
        }
    }

    private suspend fun stageResource(resource: PHAssetResource, filename: String): NSURL? {
        val dir = stagingDir ?: return null
        val fileUrl = dir.URLByAppendingPathComponent(filename) ?: return null
        deleteFile(fileUrl) // a prior partial stage for this key
        val error: NSError? = suspendCancellableCoroutine { cont ->
            PHAssetResourceManager.defaultManager().writeDataForAssetResource(
                resource,
                toFile = fileUrl,
                options = null,
            ) { err -> cont.resume(err) }
        }
        return if (error == null) fileUrl else { log.w { "stageResource $filename: ${error.localizedDescription}" }; null }
    }

    private fun deleteFile(url: NSURL) {
        NSFileManager.defaultManager.removeItemAtURL(url, error = null)
    }

}

/**
 * What a job reports when no media type was recorded for it — a stranded key, or a completion delivered
 * after the record was lost. It is the generic default because there genuinely is nothing to report, not
 * because a type was unavailable to look up.
 */
private const val STRANDED_CONTENT_TYPE = "application/octet-stream"

/**
 * The request's `Content-Type`, matched case-insensitively (HTTP header names are), falling back to the
 * generic default when the caller set none.
 */
private fun UploadRequest.contentTypeHeader(): String =
    headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
        ?.value
        ?.takeIf { it.isNotBlank() }
        ?: STRANDED_CONTENT_TYPE

/**
 * The `NSURLSession` background-session delegate — a plain `NSObject` (kept separate from
 * [IosUrlSessionUploadPlatform] because a class implementing a Kotlin interface cannot also subclass an
 * ObjC type). It only maps the two callbacks the app-driven tier needs into plain Kotlin lambdas.
 */
@OptIn(ExperimentalForeignApi::class)
private class SessionDelegate(
    private val onComplete: (key: String, success: Boolean, error: UploadError?) -> Unit,
    private val onEventsFinished: () -> Unit,
    private val log: Logger = Logger.withTag("urlSessionUpload"),
) : NSObject(), NSURLSessionTaskDelegateProtocol {

    // PLATFORM ENTRY POINT (spec `diagnostic-logging`). DEBUG, not INFO: one per uploaded photo, so
    // at INFO a large event would flush the bounded breadcrumb window and roll the device log.
    // Note the early `return` below on a task with no description — that is an entry that decides to
    // do nothing, which is exactly the shape that may not be silent, so the enter line precedes it.
    @PlatformEntry
    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) =
        log.invocation(
            "upload.didComplete",
            params = "error=${didCompleteWithError?.localizedDescription ?: "«none»"}",
            severity = Severity.Debug,
        ) {
            onTaskComplete(task, didCompleteWithError)
        }

    private fun onTaskComplete(task: NSURLSessionTask, didCompleteWithError: NSError?) {
        val status = (task.response as? NSHTTPURLResponse)?.statusCode ?: 0L
        when (val outcome = classifyUrlSessionCompletion(task.taskDescription, status, didCompleteWithError)) {
            TaskCompletion.NoLedgerKey ->
                log.w { "upload task carried no description — nothing to record" }
            is TaskCompletion.Record ->
                onComplete(outcome.key, outcome.success, outcome.error)
        }
    }

    // Session-level, once per OS re-attach: INFO.
    @PlatformEntry
    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) =
        log.invocation("upload.didFinishEvents") { onEventsFinished() }
}
