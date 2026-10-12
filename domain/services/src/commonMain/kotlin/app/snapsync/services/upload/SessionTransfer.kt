package app.snapsync.services.upload

import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.PlatformUploadJob
import app.snapsync.model.Resource
import app.snapsync.model.TerminalOutcome
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadRequest
import app.snapsync.model.WriteOutcome
import app.snapsync.model.invocation
import app.snapsync.ports.TransferFailure
import app.snapsync.ports.TransferSession
import app.snapsync.ports.TransferSource
import app.snapsync.ports.UploadTarget
import app.snapsync.services.crypto.UploadSeal
import app.snapsync.services.gallery.galleryHandle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where a file uploader's exported bytes wait, in the shared area — runtime identity: devices hold files under it. */
const val UPLOAD_STAGING_DIR: String = "upload-staging"

/** The staged file for [key] — a pure function of it, so no process has to remember the path. */
fun uploadStagingPath(key: String): String = "$UPLOAD_STAGING_DIR/$key"

/** An encrypted event's export, beside its staged file only until it is sealed into it. */
private const val PLAINTEXT_SUFFIX = ".plain"

/**
 * **The upload cycle's transfer over the app's own session** ([TransferSession] — a background `URLSession`, Android's
 * uploads): exporting a resource for a session that sends files, sealing an encrypted event's bytes into that file,
 * and recording each transfer's end the moment the session reports it ([recordFinished]) — decided once here, never in
 * the adapter.
 *
 * A session reports every end as it happens and offers no retries, so the cycle's queue-shaped members
 * ([fetchRetryJobs], [drainTerminals], [retryJob]) have nothing to do here. **They answer so as a stated stub of the
 * cycle's one [BackgroundTransfer] seam, owned by phase 4**, which splits the seam per uploader.
 */
class SessionTransfer(private val session: TransferSession, private val setup: TransferSetup) : BackgroundTransfer {
    private val log = setup.log
    private val files = setup.files

    /** None: a session offers no retries — the stated stub (see the class). */
    override suspend fun fetchRetryJobs(): List<PlatformUploadJob> = emptyList()

    /** None: a session's ends are recorded as they happen ([recordFinished]) — the stated stub (see the class). */
    override suspend fun drainTerminals(): List<PlatformUploadJob> = emptyList()

    /** Nothing to re-point: a session offers no retries — the stated stub (see the class). */
    override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) = Unit

    /**
     * Start a transfer sending [resource] to [request]'s destination. A destination that is not a URL is not a
     * transfer. Where the session sends files — always for a sealed upload — the resource is first exported to its
     * staged file ([uploadStagingPath]), which goes again when no transfer was made. The session's in-flight limit is
     * its own answer ([UploadCreateOutcome.LIMIT_EXCEEDED]): this creates until refused.
     */
    override suspend fun createJob(request: UploadRequest, resource: Resource): UploadCreateOutcome =
        log.invocation(
            setup.entryContext,
            "platform.createJob",
            params = "key=${resource.filename}",
            result = { "$it" },
        ) {
            if (!isUploadDestination(request.url)) {
                log.w { "createJob: malformed destination URL — not creating" }
                return@invocation UploadCreateOutcome.FAILED
            }
            val target = UploadTarget(request.url, request.headers, setup.network())
            val handle = resource.data.galleryHandle()
            when (val seal = setup.sealOf(resource)) {
                UploadSeal.Plain -> if (session.takesResources && handle != null) {
                    session.create(TransferSource.Resource(handle), target, resource.filename).uploadCreateOutcome()
                } else {
                    createFromFile(resource, target, seal = null)
                }
                is UploadSeal.Sealed -> createFromFile(resource, target, seal)
                is UploadSeal.Withheld -> {
                    log.i { "createJob: ${resource.filename} withheld — ${seal.reason}" }
                    UploadCreateOutcome.FAILED
                }
            }.also { if (it == UploadCreateOutcome.LIMIT_EXCEEDED) log.w { "job limit reached — deferring the rest" } }
        }

    /**
     * Held from a file's export until its transfer exists (or the file is released), and by [releaseUnclaimedStaging]
     * — so the sweep never sees an exported file whose transfer is about to be made as unclaimed.
     *
     * It is also what keeps the staged file in place when the session is handed it: every other deleter releases only
     * the file of a transfer that already exists ([recordFinished], a refused start, [cancelAll] over the session's live
     * transfers). An iOS background session raises an exception no Kotlin catch stops for a file it cannot read; its
     * adapter checks first, and this lock is why that check meets no concurrent delete.
     */
    private val staging = Mutex()

    private suspend fun createFromFile(resource: Resource, target: UploadTarget, seal: UploadSeal.Sealed?): UploadCreateOutcome =
        staging.withLock { exportAndCreate(resource, target, seal) }

    private suspend fun exportAndCreate(resource: Resource, target: UploadTarget, seal: UploadSeal.Sealed?): UploadCreateOutcome {
        val staged = uploadStagingPath(resource.filename)
        // A sealed upload is exported beside the staged file and sealed INTO it; the plaintext goes at once.
        val exportedTo = if (seal == null) staged else "$staged$PLAINTEXT_SUFFIX"
        val path = located(staged) ?: run {
            log.w { "createJob: the shared area cannot hold ${resource.filename}'s bytes — not creating" }
            return UploadCreateOutcome.FAILED
        }
        val exportPath = located(exportedTo) ?: return UploadCreateOutcome.FAILED
        // The directory the export writes into: `write` creates parents, and the empty file is replaced by the export.
        files.write(FileArea.SHARED, exportedTo, ByteArray(0))
        // A resource the library did not read carries no handle of its own to export.
        val handle = resource.data.galleryHandle()
        val exported = if (handle == null) {
            WriteOutcome.Failed("not a library resource")
        } else {
            setup.gallery.export(handle, exportPath)
        }
        when (exported) {
            WriteOutcome.Ok -> Unit
            else -> {
                log.w { "createJob: exporting ${resource.filename} failed ($exported) — not creating" }
                files.delete(FileArea.SHARED, exportedTo)
                releaseStaged(resource.filename)
                return UploadCreateOutcome.FAILED
            }
        }
        if (seal != null) {
            val sealed = setup.sealing!!.seal(seal, exportedTo, staged)
            files.delete(FileArea.SHARED, exportedTo)
            if (sealed !is FileResult.Ok) {
                log.w { "createJob: sealing ${resource.filename} failed ($sealed) — not creating" }
                releaseStaged(resource.filename)
                return UploadCreateOutcome.FAILED
            }
        }
        return session.create(TransferSource.File(path), target, resource.filename).uploadCreateOutcome().also {
            if (it != UploadCreateOutcome.CREATED) releaseStaged(resource.filename)
        }
    }

    /**
     * The session says the transfer tagged [key] ended — with [failure], or none for a success: **record it durably, right
     * here, before returning** — the session tells this once, and the process's continued runtime after its callback
     * is not ours to assume (`URLSessionTask.State.completed`: *"the task's delegate receives no further callbacks"*).
     * Non-suspending for that reason, like [TransferRecord.markTerminal] beneath it. Parking the fact for a later cycle
     * lost it on process death in the field, and bytes that had landed were uploaded again.
     *
     * The staged file goes at the same moment: the transfer is over. Answers whether the ledger took it.
     */
    fun recordFinished(key: String, failure: TransferFailure?): Boolean {
        val outcome = if (failure == null) TerminalOutcome.COMPLETED else TerminalOutcome.FAILED
        val applied = setup.record.markTerminal(key, outcome)
        releaseStaged(key)
        if (applied) {
            log.i { "task terminal: $key -> $outcome" }
        } else {
            // `Info`, not `Warn`: a pruned row is routine — an authoritative walk deletes an in-flight row whose photo
            // left the library or the selection (`changes/selection-is-the-walk`, D2).
            log.i { "task terminal: $key -> $outcome applied to NO row (not REQUESTED — already settled, or pruned)" }
        }
        if (failure != null) log.i { "task terminal: $key failed: ${failure.detail}" }
        return applied
    }

    /**
     * Cancel every in-flight transfer and delete its staged file — **at a leave only** (a switch leaves first). Asks
     * the session which transfers exist rather than a registry of our own, so it also cancels transfers a relaunch
     * inherited. Ledger rows are untouched: a cancelled transfer's end is recorded through the guarded write, which
     * matches no row once the leave has cleared the ledger.
     */
    suspend fun cancelAll() = log.invocation(setup.entryContext, "platform.cancelAll") {
        for (transfer in session.live()) {
            session.cancel(transfer)
            transfer.tag?.let(::releaseStaged)
        }
    }

    /**
     * Delete every staged upload file no in-flight transfer sends — what an export left when no transfer outlived it:
     * the process died between the export and the transfer's start, or a transfer ended without this process being
     * told. Nothing else ever reaches such a file; [recordFinished], a refused start and [cancelAll] each release only
     * the file of a transfer they are handed.
     *
     * Deleting one loses nothing: a transfer's bytes come from the photo library, and a transfer started again exports
     * again. Under [staging], so an export whose transfer is about to start is never taken for unclaimed.
     */
    suspend fun releaseUnclaimedStaging() = log.invocation(setup.entryContext, "platform.releaseUnclaimedStaging") {
        staging.withLock {
            val listed = when (val read = files.list(FileArea.SHARED, UPLOAD_STAGING_DIR)) {
                is FileResult.Ok -> read.value
                else -> {
                    log.w { "the upload staging directory could not be read ($read) — nothing released" }
                    return@withLock
                }
            }
            val claimed = session.live().mapNotNullTo(HashSet()) { transfer -> transfer.tag?.let(::uploadStagingPath) }
            val unclaimed = listed - claimed
            if (unclaimed.isEmpty()) return@withLock
            log.i { "releasing ${unclaimed.size} staged upload file(s) no in-flight transfer sends" }
            unclaimed.forEach { path ->
                when (val deleted = files.delete(FileArea.SHARED, path)) {
                    is FileResult.Ok, FileResult.NotFound -> Unit
                    else -> log.w { "the unclaimed staged upload file $path stays on disk ($deleted)" }
                }
            }
        }
    }

    /** The OS path of [path] in the shared area, or `null` when the area cannot hold it now. */
    private fun located(path: String): String? = when (val at = files.locate(FileArea.SHARED, path)) {
        is FileResult.Ok -> at.value
        else -> null
    }

    private fun releaseStaged(key: String) {
        when (val deleted = files.delete(FileArea.SHARED, uploadStagingPath(key))) {
            is FileResult.Ok, FileResult.NotFound, FileResult.AreaUnavailable -> Unit
            else -> log.w { "the staged upload file for $key stays on disk ($deleted)" }
        }
    }
}
