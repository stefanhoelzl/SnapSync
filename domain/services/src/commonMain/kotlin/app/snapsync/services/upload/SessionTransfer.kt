package app.snapsync.services.upload

import app.snapsync.domain.upload.LedgerState
import app.snapsync.model.PlatformUploadJob
import app.snapsync.model.Resource
import app.snapsync.model.TerminalOutcome
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadRequest
import app.snapsync.ports.Crypto
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileResult
import app.snapsync.ports.TransferFailure
import app.snapsync.ports.TransferSession
import app.snapsync.ports.TransferSource
import app.snapsync.ports.UploadTarget
import app.snapsync.ports.WriteOutcome
import app.snapsync.services.crypto.UploadSeal
import app.snapsync.services.gallery.galleryHandle
import app.snapsync.wire.api.Routes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where a file uploader's exported bytes wait, in the shared area — runtime identity: devices hold files under it. */
const val UPLOAD_STAGING_DIR: String = "upload-staging"

/** The staged file for [key] — a pure function of it, so no process has to remember the path. */
fun uploadStagingPath(key: String): String = "$UPLOAD_STAGING_DIR/$key"

/** An encrypted event's export, beside its staged file only until it is sealed into it. */
private const val PLAINTEXT_SUFFIX = ".plain"

/**
 * A staged file's SHA-256 and size, beside it (`<sha256 hex> <size>`): what lets a FAILED upload send the very same
 * bytes again, from where the backend says they stopped (`docs/architecture.md`, "Resumable uploads").
 */
private const val DIGEST_SUFFIX = ".sha256"

/** How much of a staged file one read hashes. */
private const val DIGEST_CHUNK = 1 shl 20

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
class SessionTransfer(
    private val session: TransferSession,
    private val setup: TransferSetup,
    /**
     * Where a failed upload stands, and the hash its staged file is declared by — together what makes an upload from a
     * file RESUME a failed attempt rather than restart it (`docs/architecture.md`, "Resumable uploads"). `null` where
     * uploads never resume.
     */
    private val resuming: Resuming? = null,
) : BackgroundTransfer {
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
        run body@{
            if (!isUploadDestination(request.url)) {
                log.w { "createJob: malformed destination URL — not creating" }
                return@body UploadCreateOutcome.FAILED
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
        }.also { log.i { "platform.createJob(key=${resource.filename}) = $it" } }

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
        val path = located(staged) ?: run {
            log.w { "createJob: the shared area cannot hold ${resource.filename}'s bytes — not creating" }
            return UploadCreateOutcome.FAILED
        }
        // A staged file an earlier, FAILED attempt left, with its digest: the same bytes go again, from where they
        // stopped. Otherwise the resource is exported (and sealed) afresh — a fresh upload never asks where it stands.
        val kept = resuming?.let { r -> keptDigest(staged)?.let { Retry(it, r.standings) } }
        val digest = if (kept != null) {
            kept.digest
        } else {
            stage(resource, staged, seal)?.let { return it }
            digestOf(staged)
        }
        val from = if (kept == null) {
            0L
        } else {
            when (val point = kept.standings.of(resource, kept.digest.sha256)) {
                ResumePoint.Deferred -> return UploadCreateOutcome.FAILED
                // The bytes landed and the answer was lost: one byte resent lands the transfer like any other.
                ResumePoint.Landed -> kept.digest.size - 1
                is ResumePoint.From -> point.offset
            }
        }
        if (from > 0) log.i { "createJob: ${resource.filename} resumes at $from" }
        val resumable = target.copy(headers = target.headers + resumeHeaders(digest, from))
        return session.create(
            TransferSource.File(path, from),
            resumable,
            resource.filename,
        ).uploadCreateOutcome().also {
            // A file with a digest stays for the next attempt, which resumes it; without one it would be exported again.
            if (it != UploadCreateOutcome.CREATED && digest == null) releaseStaged(resource.filename)
        }
    }

    /** Export [resource] to [staged], sealing it on the way where [seal] says; a failure outcome when that failed. */
    private suspend fun stage(resource: Resource, staged: String, seal: UploadSeal.Sealed?): UploadCreateOutcome? {
        // A sealed upload is exported beside the staged file and sealed INTO it; the plaintext goes at once.
        val exportedTo = if (seal == null) staged else "$staged$PLAINTEXT_SUFFIX"
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
        return null
    }

    /** The headers a resumable upload carries: its file's SHA-256, and from a later byte the range it sends. */
    private fun resumeHeaders(digest: StagedDigest?, from: Long): Map<String, String> = when {
        digest == null -> emptyMap()
        from <= 0 -> mapOf(Routes.UploadResource.HEADER_X_SNAPSYNC_SHA256 to digest.sha256)
        else -> mapOf(
            Routes.UploadResource.HEADER_X_SNAPSYNC_SHA256 to digest.sha256,
            Routes.UploadResource.HEADER_CONTENT_RANGE to "bytes $from-${digest.size - 1}/${digest.size}",
        )
    }

    /**
     * Hash [staged] and keep the digest beside it — `null` where uploads do not resume, or when the file could not be
     * read whole (the upload then goes as it always did, and restarts if it fails).
     */
    private fun digestOf(staged: String): StagedDigest? {
        val crypto = (resuming ?: return null).crypto
        var size = 0L
        val hash = try {
            crypto.sha256(
                generateSequence(0L) { it + DIGEST_CHUNK }.map { offset ->
                    when (val read = files.readRange(FileArea.SHARED, staged, offset, DIGEST_CHUNK)) {
                        is FileResult.Ok -> read.value
                        else -> throw UnreadableStagedFile("$read")
                    }
                }.takeWhile { it.isNotEmpty() }.onEach { size += it.size },
            )
        } catch (e: UnreadableStagedFile) {
            log.w { "createJob: $staged could not be hashed (${e.message}) — it will not resume" }
            return null
        }
        val digest = StagedDigest(hash.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }, size)
        files.write(FileArea.SHARED, "$staged$DIGEST_SUFFIX", "${digest.sha256} ${digest.size}".encodeToByteArray())
        return digest
    }

    /** The digest of a staged file a failed attempt kept — only while the file is still there. */
    private fun keptDigest(staged: String): StagedDigest? {
        val read = files.read(FileArea.SHARED, "$staged$DIGEST_SUFFIX")
        if (read !is FileResult.Ok) return null
        val line = DIGEST_LINE.matchEntire(read.value.decodeToString())
        if (line == null || files.exists(FileArea.SHARED, staged) != FileResult.Ok(true)) {
            files.delete(FileArea.SHARED, "$staged$DIGEST_SUFFIX")
            return null
        }
        return StagedDigest(line.groupValues[1], line.groupValues[2].toLong())
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
        // A failed upload's staged file stays where it resumes (its digest beside it): the next attempt sends the same
        // bytes from where they stopped. A success, and every upload that never resumes, release it.
        if (resuming == null || failure == null) releaseStaged(key)
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
    suspend fun cancelAll() = run {
        for (transfer in session.live()) {
            session.cancel(transfer)
            transfer.tag?.let(::releaseStaged)
        }
    }.also { log.i { "platform.cancelAll" } }

    /**
     * Delete every staged upload file no in-flight transfer sends — what an export left when no transfer outlived it:
     * the process died between the export and the transfer's start, or a transfer ended without this process being
     * told. Nothing else ever reaches such a file; [recordFinished], a refused start and [cancelAll] each release only
     * the file of a transfer they are handed.
     *
     * Deleting one loses nothing: a transfer's bytes come from the photo library, and a transfer started again exports
     * again. Under [staging], so an export whose transfer is about to start is never taken for unclaimed.
     */
    suspend fun releaseUnclaimedStaging() = run {
        staging.withLock {
            val listed = when (val read = files.list(FileArea.SHARED, UPLOAD_STAGING_DIR)) {
                is FileResult.Ok -> read.value
                else -> {
                    log.w { "the upload staging directory could not be read ($read) — nothing released" }
                    return@withLock
                }
            }
            val claimed = session.live().mapNotNullTo(HashSet()) { transfer -> transfer.tag?.let(::uploadStagingPath) }
            val unclaimed = (listed - claimed).filterNot { kept(it, claimed) }
            if (unclaimed.isEmpty()) return@withLock
            log.i { "releasing ${unclaimed.size} staged upload file(s) no in-flight transfer sends" }
            unclaimed.forEach { path ->
                when (val deleted = files.delete(FileArea.SHARED, path)) {
                    is FileResult.Ok, FileResult.NotFound -> Unit
                    else -> log.w { "the unclaimed staged upload file $path stays on disk ($deleted)" }
                }
            }
        }
    }.also { log.i { "platform.releaseUnclaimedStaging" } }

    /**
     * Whether the staged file at [path] is kept for a resume: the digest of a file in flight, or a file (or its digest)
     * whose row still needs its upload — a failed attempt's, which the next one sends on from where it stopped.
     */
    private suspend fun kept(path: String, claimed: Set<String>): Boolean {
        if (resuming == null) return false
        val file = path.removeSuffix(DIGEST_SUFFIX)
        if (file in claimed) return true
        val row = setup.record.entryForKey(file.removePrefix("$UPLOAD_STAGING_DIR/")) ?: return false
        return row.state != LedgerState.COMPLETED
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
        files.delete(FileArea.SHARED, "${uploadStagingPath(key)}$DIGEST_SUFFIX")
    }
}

/** What makes a file upload resumable: where a failed one stands, and the hash its file is declared by. */
class Resuming(val standings: Standings, val crypto: Crypto)

/** Where a failed upload of a resource's file with a SHA-256 stands — [UploadStandings] in a composition. */
fun interface Standings {
    suspend fun of(resource: Resource, sha256: String): ResumePoint
}

/** A staged file's SHA-256 (lowercase hex) and size. */
private class StagedDigest(val sha256: String, val size: Long)

/** A kept staged file's digest, and where the backend says its bytes stand. */
private class Retry(val digest: StagedDigest, val standings: Standings)

private class UnreadableStagedFile(detail: String) : Exception(detail)

/** A digest file's one line: `<sha256 hex> <size>`. */
private val DIGEST_LINE = Regex("^([0-9a-f]{64}) ([0-9]+)$")
