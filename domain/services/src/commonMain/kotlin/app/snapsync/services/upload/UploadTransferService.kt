package app.snapsync.services.upload

import app.snapsync.model.ChangeOutcome
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.Resource
import app.snapsync.model.TerminalOutcome
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadJobState
import app.snapsync.model.UploadRequest
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadSourceKind
import app.snapsync.model.UploadTarget
import app.snapsync.model.WriteOutcome
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.ports.EntryContext
import app.snapsync.ports.Files
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.PlatformUploadJob
import app.snapsync.ports.TransferRecord
import app.snapsync.ports.Upload
import app.snapsync.ports.UploadDiscovery
import app.snapsync.ports.invocation
import co.touchlab.kermit.Logger

/** Where a file uploader's exported bytes wait, in the shared area — runtime identity: devices hold files under it. */
const val UPLOAD_STAGING_DIR: String = "upload-staging"

/** The staged file for [key] — a pure function of it, so no process has to remember the path. */
fun uploadStagingPath(key: String): String = "$UPLOAD_STAGING_DIR/$key"

/**
 * **The upload cycle's transfer, over the thin [Upload] port** (capability `background-upload`): what used to live
 * inside each platform adapter — which ledger row a presented job belongs to, what a terminal job means, which offered
 * retry is a key's, exporting a resource for a platform that uploads from a file, and recording a terminal the moment
 * the platform reports it — decided once here, for every platform (phase 11f).
 *
 * It implements the cycle's transitional [BackgroundTransfer] seam, as the storage and gallery services implemented
 * theirs in 11b and 11d, so `UploadCycle` is unchanged.
 *
 * **It records.** A terminal fact is written into the ledger where the platform tells it — [drainTerminals] for a
 * platform that presents terminal jobs (PhotoKit), [recordFinished] for one that reports them as they happen
 * (`URLSession`) — through the narrow [TransferRecord]'s guarded, non-suspending write, and never handed to the cycle
 * (`photo-sharing`). A fact parked for a later cycle to collect does not survive the process.
 *
 * **Every presented job is acknowledged**, whatever its guarded write did — a write that applies to nothing is still a
 * job the platform expects back. PhotoKit reports `appex failed to acknowledge jobs for processing state` (error 50008)
 * otherwise, discards the outstanding jobs and defers the extension ~300 s (measured, iOS 26.6).
 */
class UploadTransferService(
    private val upload: Upload,
    private val record: TransferRecord,
    /** Where a failed job's still-live resource is found for its re-creation: the cycle's own by-key resolve. */
    private val resources: UploadDiscovery,
    /** The photo library, which exports a resource to a file for a platform that uploads from one. */
    private val gallery: GalleryReader,
    /** The shared area a file uploader's exported bytes wait in ([UPLOAD_STAGING_DIR]). */
    private val files: Files,
    private val log: Logger = Logger.withTag("UploadTransfer"),
    private val entryContext: EntryContext = EntryContext.NoOp,
) : BackgroundTransfer {

    override suspend fun fetchRetryJobs(): List<PlatformUploadJob> =
        log.invocation(entryContext, "platform.fetchRetryJobs", result = { "${it.size} job(s)" }) {
            val report = Report("fetch")
            val out = ArrayList<PlatformUploadJob>()
            for (job in upload.jobs(UploadJobSet.RETRY_OFFERED)) {
                when (val classified = classifyFetchedJob(job.destinationPath, job.state, job.error)) {
                    // EVERY presented job must be acknowledged, an unmappable one included (error 50008).
                    FetchedJob.AcknowledgeToDrain -> { report.unrecoverable++; acknowledge(job) }
                    is FetchedJob.Emit -> when (val row = rowFor(classified)) {
                        is JobRow.Found -> out += PlatformUploadJob(
                            key = row.key,
                            contentType = jobContentType(job.contentType, null),
                            error = classified.error,
                            data = (job.source as? UploadSource.Resource)?.handle,
                        )
                        // Never handed to the cycle, which would decline to retry it and leave it un-acknowledged:
                        // a job for a photo that left is answered HERE.
                        JobRow.Pruned -> { report.pruned++; acknowledge(job) }
                        JobRow.Unmappable -> { report.unrecoverable++; acknowledge(job) }
                    }
                }
            }
            report.emit()
            out
        }

    /**
     * Record every terminal job into the ledger and acknowledge it; return only the retry-spent failures whose
     * resource is still live, so the cycle can re-create them in this same cycle. A succeeded job is recorded
     * `COMPLETED` and nothing about it reaches the cycle.
     */
    override suspend fun drainTerminals(): List<PlatformUploadJob> =
        log.invocation(entryContext, "platform.drainTerminals", result = { "${it.size} job(s)" }) {
            val report = Report("drainTerminals")
            val out = ArrayList<PlatformUploadJob>()
            for (job in upload.jobs(UploadJobSet.TERMINAL)) {
                when (val classified = classifyFetchedJob(job.destinationPath, job.state, job.error)) {
                    FetchedJob.AcknowledgeToDrain -> report.unrecoverable++
                    is FetchedJob.Emit -> when (val row = rowFor(classified)) {
                        is JobRow.Found -> settle(job, classified, row.key)?.let { out += it }
                        // The photo left the library or the selection, maybe mid-upload: answered, nothing written.
                        JobRow.Pruned -> report.pruned++
                        JobRow.Unmappable -> report.unrecoverable++
                    }
                }
                acknowledge(job)
            }
            report.emit()
            out
        }

    /** Record [job]'s terminal fact for [key]; answer the job to re-create when it failed and its resource lives. */
    private suspend fun settle(job: UploadJob, classified: FetchedJob.Emit, key: String): PlatformUploadJob? {
        if (classified.state == UploadJobState.UNKNOWN) {
            log.w { "terminal $key is in a state this build does not know — adjudicated as a failure" }
        }
        // A failure's live resource: the job's own where the platform still holds it, otherwise the photo's, if it is
        // still in the library (PhotoKit answers no resource for a retry-spent job — measured SE2, iOS 26.6.2).
        val live: Resource? = (job.source as? UploadSource.Resource)
            ?.let { Resource(key, assetIdFromUploadKey(key), job.contentType.orEmpty(), emptyMap(), it.handle) }
            ?: if (classified.state == UploadJobState.SUCCEEDED) null else liveResource(key)
        val disposition = terminalDisposition(classified.state, resourceIsLive = live != null)
        if (!record.markTerminal(key, disposition.outcome)) {
            // Not silent: the row was not REQUESTED — already settled, or pruned.
            log.i { "terminal $key -> ${disposition.outcome} applied to no row" }
        }
        if (!disposition.reCreate || live == null) return null
        return PlatformUploadJob(
            key = key,
            contentType = jobContentType(job.contentType, live.contentType.ifEmpty { null }),
            error = classified.error,
            data = live.data,
        )
    }

    /**
     * Re-point the offered retry whose destination resolves to [job]'s key at [request] — looked up again rather than
     * carried, by the same route the drain resolves rows by, so a retry and a drain can never disagree about which row a
     * job belongs to.
     */
    override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) =
        log.invocation(entryContext, "platform.retryJob", params = "key=${job.key}") {
            val candidates = upload.jobs(UploadJobSet.RETRY_OFFERED)
                .map { it to classifyFetchedJob(it.destinationPath, it.state, it.error) }
            val offered = retryJobMatching(candidates, job.key) { (rowFor(it) as? JobRow.Found)?.key } ?: run {
                log.w { "retryJob: no offered retry for ${job.key} — it settled underneath us" }
                return@invocation
            }
            if (!isUploadDestination(request.url)) {
                log.w { "retryJob: malformed destination URL for ${job.key} — not retrying" }
                return@invocation
            }
            val answer = upload.retry(offered, UploadTarget(request.url, request.headers))
            if (answer is ChangeOutcome.Refused) {
                log.w { "retryJob: the retry was refused for ${job.key} (code=${answer.code} ${answer.detail})" }
            }
        }

    /**
     * Create a job sending [resource] to [request]'s destination. A destination that is not a URL is not a job. Where
     * the platform uploads from a file, the resource is first exported to its staged file ([uploadStagingPath]), which
     * goes again when no job was created. The platform's in-flight limit is its own answer
     * ([UploadCreateOutcome.LIMIT_EXCEEDED]): this creates until refused.
     */
    override suspend fun createJob(request: UploadRequest, resource: Resource): UploadCreateOutcome =
        log.invocation(entryContext, "platform.createJob", params = "key=${resource.filename}", result = { "$it" }) {
            if (!isUploadDestination(request.url)) {
                log.w { "createJob: malformed destination URL — not creating" }
                return@invocation UploadCreateOutcome.FAILED
            }
            val target = UploadTarget(request.url, request.headers)
            when (upload.accepts) {
                UploadSourceKind.RESOURCE -> upload.create(UploadSource.Resource(resource.data), target, resource.filename)
                UploadSourceKind.FILE -> createFromFile(resource, target)
            }.also { if (it == UploadCreateOutcome.LIMIT_EXCEEDED) log.w { "job limit reached — deferring the rest" } }
        }

    private suspend fun createFromFile(resource: Resource, target: UploadTarget): UploadCreateOutcome {
        val staged = uploadStagingPath(resource.filename)
        val path = (files.locate(FileArea.SHARED, staged) as? FileResult.Ok)?.value ?: run {
            log.w { "createJob: the shared area cannot hold ${resource.filename}'s bytes — not creating" }
            return UploadCreateOutcome.FAILED
        }
        // The directory the export writes into: `write` creates parents, and the empty file is replaced by the export.
        files.write(FileArea.SHARED, staged, ByteArray(0))
        when (val exported = gallery.export(resource, path)) {
            WriteOutcome.Ok -> Unit
            else -> {
                log.w { "createJob: exporting ${resource.filename} failed ($exported) — not creating" }
                releaseStaged(resource.filename)
                return UploadCreateOutcome.FAILED
            }
        }
        return upload.create(UploadSource.File(path), target, resource.filename).also {
            if (it != UploadCreateOutcome.CREATED) releaseStaged(resource.filename)
        }
    }

    /**
     * A platform that reports as it happens (a `URLSession` delegate) says [job] ended: **record it durably, right
     * here, before returning** — the platform tells this once, and the process's continued runtime after its callback
     * is not ours to assume (`URLSessionTask.State.completed`: *"the task's delegate receives no further callbacks"*).
     * Non-suspending for that reason, like [TransferRecord.markTerminal] beneath it. Parking the fact for a later cycle
     * lost it on process death in the field, and bytes that had landed were uploaded again.
     *
     * The staged file goes at the same moment: the transfer is over. Answers whether the ledger took it.
     */
    fun recordFinished(job: UploadJob): Boolean {
        val key = job.tag ?: run {
            log.w { "an upload ended carrying no tag — nothing to record" }
            return false
        }
        val outcome = if (job.state == UploadJobState.SUCCEEDED) TerminalOutcome.COMPLETED else TerminalOutcome.FAILED
        val applied = record.markTerminal(key, outcome)
        releaseStaged(key)
        if (applied) {
            log.i { "task terminal: $key -> $outcome" }
        } else {
            // `Info`, not `Warn`: a pruned row is routine — an authoritative walk deletes an in-flight row whose photo
            // left the library or the selection (`changes/selection-is-the-walk`, D2).
            log.i { "task terminal: $key -> $outcome applied to NO row (not REQUESTED — already settled, or pruned)" }
        }
        if (outcome == TerminalOutcome.FAILED) log.i { "task terminal: $key failed with ${job.error ?: "«unspecified»"}" }
        return applied
    }

    /**
     * Cancel every in-flight job and delete its staged file — **at a leave only** (a switch leaves first). Asks the
     * platform which jobs exist rather than a registry of our own, so it also cancels transfers a relaunch inherited.
     * Ledger rows are untouched: a cancelled job's terminal is recorded through the guarded write, which matches no row
     * once the leave has cleared the ledger.
     */
    suspend fun cancelAll() = log.invocation(entryContext, "platform.cancelAll") {
        for (job in upload.jobs(UploadJobSet.IN_FLIGHT)) {
            upload.cancel(job)
            job.tag?.let(::releaseStaged)
        }
    }

    private suspend fun liveResource(key: String): Resource? =
        resources.resourcesFor(setOf(key)).firstOrNull { it.filename == key }

    private suspend fun rowFor(emit: FetchedJob.Emit): JobRow =
        jobRowOf(emit.destinationPath, record.entryForDestination(emit.destinationPath)?.key)

    private suspend fun acknowledge(job: UploadJob) {
        val answer = upload.acknowledge(job)
        if (answer is ChangeOutcome.Refused) {
            log.w { "acknowledge refused (code=${answer.code} ${answer.detail}) — the platform offers the job again" }
        }
    }

    private fun releaseStaged(key: String) {
        when (val deleted = files.delete(FileArea.SHARED, uploadStagingPath(key))) {
            is FileResult.Ok, FileResult.NotFound, FileResult.AreaUnavailable -> Unit
            else -> log.w { "the staged upload file for $key stays on disk ($deleted)" }
        }
    }

    /** One pass's jobs that were not the cycle's business, reported once rather than per job. */
    private inner class Report(private val site: String) {
        var unrecoverable = 0
        var pruned = 0

        /**
         * Unmappable jobs at `Error` — an outcome discarded for a reason this build cannot name, which nothing else
         * reports — and pruned ones at `Info`: a photo deleted or de-selected while uploading is expected, and at
         * `Error` it would put a crash-reporting event behind each one (`changes/selection-is-the-walk`, D3).
         */
        fun emit() {
            if (unrecoverable > 0) {
                log.e {
                    "$site: $unrecoverable upload job(s) carried no destination this build can map — their outcomes " +
                        "are discarded; nothing else will report this"
                }
            }
            if (pruned > 0) log.i { "$site: $pruned upload job(s) belong to rows the walk removed — acknowledged" }
        }
    }
}
