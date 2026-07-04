package app.snapsync.ios.urlsession

import app.snapsync.engine.Resource
import app.snapsync.engine.UploadError
import app.snapsync.engine.UploadRequest
import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.upload.CreateResult
import app.snapsync.upload.Discovery
import app.snapsync.upload.PlatformJobState
import app.snapsync.upload.PlatformUploadJob
import app.snapsync.upload.UploadJobPlatform
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLock
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.NSURLSessionUploadTask
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceManager
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * The app-driven (iOS 18–26.0) implementation of the `:capability:upload` [UploadJobPlatform] seam,
 * backed by a **background `URLSession`** — the OS-owned durable queue the PhotoKit tier gets for free,
 * reimplemented in the app process. `UploadCycle` runs unchanged over it; only the job lifecycle
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
 * ([inFlight], [terminal]) is guarded by [lock]. Not unit-tested (device-verified); faked in the harness.
 */
@OptIn(ExperimentalForeignApi::class)
class IosUrlSessionUploadPlatform(
    private val log: Logger,
    private val discovery: IosDiscovery,
    private val appGroup: String,
    sessionIdentifier: String,
    private val cap: Int = 4,
    // The ledger's current REQUESTED keys — used to reconcile stranded rows (a task lost across process
    // death) precisely, instead of a blanket clear. Supplied by the composition root (which reads it).
    private val pendingKeys: suspend () -> Set<String>,
    // Fired after each task reaches a terminal state — the composition root wires this to the pump's
    // `onUploadCompleted` (a slot just freed → top up).
    private val onTerminal: () -> Unit,
) : UploadJobPlatform {

    /** Set by the composition root: invoked from the background-session relaunch delegate callback. */
    var onBackgroundEventsFinished: (() -> Unit)? = null

    private class InFlight(val task: NSURLSessionUploadTask, val resource: PHAssetResource?, val fileUrl: NSURL)
    private class Terminal(val key: String, val success: Boolean, val error: UploadError?, val resource: PHAssetResource?)

    private val lock = NSLock()
    private val inFlight = HashMap<String, InFlight>()
    private val terminal = ArrayList<Terminal>()

    private val delegate = SessionDelegate(
        onComplete = ::recordTerminal,
        onEventsFinished = { onBackgroundEventsFinished?.invoke() },
    )

    private val session: NSURLSession by lazy {
        val config = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(sessionIdentifier)
        NSURLSession.sessionWithConfiguration(config, delegate = delegate, delegateQueue = null)
    }

    private val stagingDir: NSURL? by lazy {
        val container = NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(appGroup)
        container?.URLByAppendingPathComponent("upload-staging", isDirectory = true)?.also { dir ->
            NSFileManager.defaultManager.createDirectoryAtURL(dir, withIntermediateDirectories = true, attributes = null, error = null)
        }
    }

    override suspend fun discoverResources(sinceToken: ByteArray?): Discovery = discovery.discover(sinceToken)

    // No OS-sponsored free retry on this platform: failures return via fetchAckJobs and are recreated.
    override suspend fun fetchRetryJobs(): List<PlatformUploadJob> = emptyList()

    override suspend fun createJob(request: UploadRequest, resource: Resource): CreateResult {
        val phResource = resource.data as? PHAssetResource ?: run {
            log.w { "createJob: resource payload is not a PHAssetResource — not creating" }
            return CreateResult.FAILED
        }
        if (locked { inFlight.size } >= cap) return CreateResult.LIMIT_EXCEEDED

        val fileUrl = stageResource(phResource, resource.filename) ?: run {
            log.w { "createJob: staging failed for ${resource.filename} — not creating" }
            return CreateResult.FAILED
        }
        val url = NSURL.URLWithString(request.url) ?: run {
            deleteFile(fileUrl)
            log.w { "createJob: malformed destination URL — not creating" }
            return CreateResult.FAILED
        }
        val urlRequest = discovery.buildRequest(url, request)
        val task = session.uploadTaskWithRequest(urlRequest, fromFile = fileUrl)
        task.taskDescription = resource.filename // the ledger key — the only field present across the lifecycle
        locked { inFlight[resource.filename] = InFlight(task, phResource, fileUrl) }
        task.resume()
        return CreateResult.CREATED
    }

    // Unused on this tier (fetchRetryJobs is always empty), implemented as cancel-and-recreate for the seam.
    override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) {
        cancelKey(job.key)
        val resource = job.data as? PHAssetResource ?: return
        createJob(request, Resource(job.key, "", job.contentType, emptyMap(), resource))
    }

    override suspend fun fetchAckJobs(): List<PlatformUploadJob> {
        val drained = locked { ArrayList(terminal).also { terminal.clear() } }
        val terminalJobs = drained.map {
            PlatformUploadJob(
                key = it.key,
                contentType = "application/octet-stream",
                state = if (it.success) PlatformJobState.SUCCEEDED else PlatformJobState.FAILED,
                error = it.error,
                data = it.resource, // present → UploadCycle can recreate a failed job in-cycle
                handle = Unit,
            )
        }
        // Precise reconciliation (replaces blanket clearRequested): a REQUESTED key with no live task and
        // no completion this round was lost (e.g. process death / user force-quit) — surface it FAILED so
        // the ledger flips REQUESTED→FAILED and a later full enumeration re-uploads it (idempotent PUT).
        val live = liveTaskKeys()
        val drainedKeys = drained.mapTo(HashSet()) { it.key }
        val stranded = pendingKeys().filter { it !in live && it !in drainedKeys }
        val strandedJobs = stranded.map {
            log.i { "reconcile: stranded REQUESTED $it (no live task) — surfacing FAILED to re-upload" }
            PlatformUploadJob(it, "application/octet-stream", PlatformJobState.FAILED, UploadError.Unknown("stranded"), data = null, handle = Unit)
        }
        return terminalJobs + strandedJobs
    }

    override suspend fun acknowledge(job: PlatformUploadJob) {
        val removed = locked { inFlight.remove(job.key) }
        removed?.let { deleteFile(it.fileUrl) }
    }

    /**
     * Force the (lazy) background session to be adopted for this process — on a
     * `handleEventsForBackgroundURLSession` relaunch, this re-attaches the session by its identifier so
     * it re-delivers the completion callbacks for transfers finished while the app was suspended.
     */
    fun reattach() {
        session // touching the lazy val instantiates + adopts the background session
    }

    /** Cancel all in-flight transfers + clear staged files (on leave / disable / event switch). */
    fun cancelAll() {
        val entries = locked { val v = inFlight.values.toList(); inFlight.clear(); terminal.clear(); v }
        entries.forEach { it.task.cancel(); deleteFile(it.fileUrl) }
    }

    private fun cancelKey(key: String) {
        locked { inFlight.remove(key) }?.let { it.task.cancel(); deleteFile(it.fileUrl) }
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

    // Called from the delegate (background queue) with the task's key + outcome; enriches with the
    // registry's PHAssetResource (so a failed job can be recreated in-cycle) and wakes the pump.
    private fun recordTerminal(key: String, success: Boolean, error: UploadError?) {
        val entry = locked {
            val e = inFlight.remove(key)
            terminal += Terminal(key, success, error, e?.resource)
            e
        }
        entry?.let { deleteFile(it.fileUrl) }
        log.i { "task terminal: $key success=$success" }
        onTerminal()
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

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/**
 * The `NSURLSession` background-session delegate — a plain `NSObject` (kept separate from
 * [IosUrlSessionUploadPlatform] because a class implementing a Kotlin interface cannot also subclass an
 * ObjC type). It only maps the two callbacks the app-driven tier needs into plain Kotlin lambdas.
 */
@OptIn(ExperimentalForeignApi::class)
private class SessionDelegate(
    private val onComplete: (key: String, success: Boolean, error: UploadError?) -> Unit,
    private val onEventsFinished: () -> Unit,
) : NSObject(), NSURLSessionTaskDelegateProtocol {

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        val key = task.taskDescription ?: return
        val status = (task.response as? NSHTTPURLResponse)?.statusCode ?: 0L
        val success = didCompleteWithError == null && status in 200L..299L
        val error = if (success) null
        else UploadError.Unknown(didCompleteWithError?.let { "${it.domain}:${it.code}" } ?: "http:$status")
        onComplete(key, success, error)
    }

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        onEventsFinished()
    }
}
