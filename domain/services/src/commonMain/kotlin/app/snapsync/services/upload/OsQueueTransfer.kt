package app.snapsync.services.upload

import app.snapsync.model.PlatformUploadJob
import app.snapsync.model.Resource
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadRequest
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.destinationPathOf
import app.snapsync.ports.ChangeOutcome
import app.snapsync.ports.OsUploadJob
import app.snapsync.ports.OsUploadQueue
import app.snapsync.ports.UploadJobState
import app.snapsync.ports.UploadTarget
import app.snapsync.services.crypto.UploadSeal
import app.snapsync.services.gallery.GalleryResourceRef
import app.snapsync.services.gallery.galleryHandle

/**
 * **The upload cycle's transfer over the OS's job queue** ([OsUploadQueue] — PhotoKit's, in the upload extension):
 * which ledger row a presented job belongs to, what a terminal job means, which offered retry is a key's — decided
 * once here, never in the adapter.
 *
 * **It records.** A terminal fact is written into the ledger where the queue presents it ([drainTerminals]), through
 * the narrow [TransferRecord]'s guarded, non-suspending write, and never handed to the cycle.
 *
 * **Every presented job is acknowledged**, whatever its guarded write did — a write that applies to nothing is still a
 * job the queue expects back. PhotoKit reports `appex failed to acknowledge jobs for processing state` (error 50008)
 * otherwise, discards the outstanding jobs and defers the extension ~300 s (measured, iOS 26.6).
 *
 * The queue sends the library's own bytes and takes no file, so an encrypted event's upload is sealed by the edge from
 * the one file's key the headers carry.
 */
class OsQueueTransfer(private val queue: OsUploadQueue, private val setup: TransferSetup) : BackgroundTransfer {
    private val log = setup.log

    override suspend fun fetchRetryJobs(): List<PlatformUploadJob> {
        val report = Report(log, "fetch")
        val out = ArrayList<PlatformUploadJob>()
        for (job in queue.retryOffered()) {
            when (val classified = classify(job)) {
                // EVERY presented job must be acknowledged, an unmappable one included (error 50008).
                FetchedJob.AcknowledgeToDrain -> {
                    report.unrecoverable++
                    acknowledge(job)
                }
                is FetchedJob.Emit -> when (val row = rowFor(classified)) {
                    is JobRow.Found -> out += PlatformUploadJob(
                        key = row.key,
                        contentType = jobContentType(job.contentType, null),
                        error = classified.error,
                        data = job.resource?.let(::GalleryResourceRef),
                    )
                    // Never handed to the cycle, which would decline to retry it and leave it un-acknowledged:
                    // a job for a photo that left is answered HERE.
                    JobRow.Pruned -> {
                        report.pruned++
                        acknowledge(job)
                    }
                    JobRow.Unmappable -> {
                        report.unrecoverable++
                        acknowledge(job)
                    }
                }
            }
        }
        report.emit()
        log.i { "platform.fetchRetryJobs = ${out.size} job(s)" }
        return out
    }

    /**
     * Record every terminal job into the ledger and acknowledge it; return only the retry-spent failures whose
     * resource is still live, so the cycle can re-create them in this same cycle. A succeeded job is recorded
     * `COMPLETED` and nothing about it reaches the cycle.
     */
    override suspend fun drainTerminals(): List<PlatformUploadJob> {
        val report = Report(log, "drainTerminals")
        val out = ArrayList<PlatformUploadJob>()
        for (job in queue.terminals()) {
            drainOne(job, report)?.let { out += it }
            acknowledge(job)
        }
        report.emit()
        log.i { "platform.drainTerminals = ${out.size} job(s)" }
        return out
    }

    /** One terminal [job]: recorded, counted into [report] — and answered when the cycle should re-create it. */
    private suspend fun drainOne(job: OsUploadJob, report: Report): PlatformUploadJob? =
        when (val classified = classify(job)) {
            FetchedJob.AcknowledgeToDrain -> null.also { report.unrecoverable++ }
            is FetchedJob.Emit -> when (val row = rowFor(classified)) {
                is JobRow.Found -> settle(job, classified, row.key)
                // The photo left the library or the selection, maybe mid-upload: answered, nothing written.
                JobRow.Pruned -> null.also { report.pruned++ }
                JobRow.Unmappable -> null.also { report.unrecoverable++ }
            }
        }

    /** Record [job]'s terminal fact for [key]; answer the job to re-create when it failed and its resource lives. */
    private suspend fun settle(job: OsUploadJob, classified: FetchedJob.Emit, key: String): PlatformUploadJob? {
        if (classified.state == UploadJobState.UNKNOWN) {
            log.w { "terminal $key is in a state this build does not know — adjudicated as a failure" }
        }
        // A failure's live resource: the job's own where the queue still holds it, otherwise the photo's, if it is
        // still in the library (PhotoKit answers no resource for a retry-spent job — measured SE2, iOS 26.6.2).
        val held = job.resource
        val live: Resource? = when {
            held != null ->
                Resource(
                    key,
                    assetIdFromUploadKey(key),
                    job.contentType.orEmpty(),
                    emptyMap(),
                    GalleryResourceRef(held),
                )
            classified.state == UploadJobState.SUCCEEDED -> null
            else -> setup.resources.resourcesFor(setOf(key)).firstOrNull { it.filename == key }
        }
        val disposition = terminalDisposition(classified.state, resourceIsLive = live != null)
        if (!setup.record.markTerminal(key, disposition.outcome)) {
            // Not silent: the row was not REQUESTED — already settled, or pruned.
            log.i { "terminal $key -> ${disposition.outcome} applied to no row" }
        }
        val reCreated = live?.takeIf { disposition.reCreate } ?: return null
        return PlatformUploadJob(
            key = key,
            contentType = jobContentType(job.contentType, reCreated.contentType.ifEmpty { null }),
            error = classified.error,
            data = reCreated.data,
        )
    }

    /**
     * Re-point the offered retry whose destination resolves to [job]'s key at [request] — looked up again rather than
     * carried, by the same route the drain resolves rows by, so a retry and a drain can never disagree about which row a
     * job belongs to.
     */
    override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) =
        run body@{
            val candidates = queue.retryOffered().map { it to classify(it) }
            val offered = retryJobMatching(candidates, job.key) { (rowFor(it) as? JobRow.Found)?.key } ?: run {
                log.w { "retryJob: no offered retry for ${job.key} — it settled underneath us" }
                return@body
            }
            if (!isUploadDestination(request.url)) {
                log.w { "retryJob: malformed destination URL for ${job.key} — not retrying" }
                return@body
            }
            // The queue's own retry re-sends the library's bytes, so an encrypted event's edge seal is renewed with it.
            val headers = when (val seal = setup.sealOf(request.resource)) {
                UploadSeal.Plain -> request.headers
                is UploadSeal.Sealed -> request.headers + setup.sealing!!.edgeHeaders(seal)
                is UploadSeal.Withheld -> {
                    log.i { "retryJob: ${job.key} withheld — ${seal.reason}" }
                    return@body
                }
            }
            val answer = queue.retry(offered, UploadTarget(request.url, headers, setup.network()))
            if (answer is ChangeOutcome.Refused) {
                log.w { "retryJob: the retry was refused for ${job.key} (code=${answer.code} ${answer.detail})" }
            }
        }.also { log.i { "platform.retryJob(key=${job.key})" } }

    /**
     * Make a job sending [resource]'s own bytes to [request]'s destination — sealed by the edge in an encrypted event.
     * A destination that is not a URL, and a resource the library did not read, are not a job. The queue's in-flight
     * limit is its own answer ([UploadCreateOutcome.LIMIT_EXCEEDED]): this creates until refused.
     */
    override suspend fun createJob(request: UploadRequest, resource: Resource): UploadCreateOutcome =
        run body@{
            if (!isUploadDestination(request.url)) {
                log.w { "createJob: malformed destination URL — not creating" }
                return@body UploadCreateOutcome.FAILED
            }
            val handle = resource.data.galleryHandle() ?: run {
                log.w { "createJob: ${resource.filename} is no library resource — not creating" }
                return@body UploadCreateOutcome.FAILED
            }
            val headers = when (val seal = setup.sealOf(resource)) {
                UploadSeal.Plain -> request.headers
                is UploadSeal.Sealed -> request.headers + setup.sealing!!.edgeHeaders(seal)
                is UploadSeal.Withheld -> {
                    log.i { "createJob: ${resource.filename} withheld — ${seal.reason}" }
                    return@body UploadCreateOutcome.FAILED
                }
            }
            queue.create(handle, UploadTarget(request.url, headers, setup.network())).uploadCreateOutcome()
                .also { if (it == UploadCreateOutcome.LIMIT_EXCEEDED) log.w { "job limit reached — deferring the rest" } }
        }.also { log.i { "platform.createJob(key=${resource.filename}) = $it" } }

    private fun classify(job: OsUploadJob): FetchedJob =
        classifyFetchedJob(job.destinationUrl?.let(::destinationPathOf), job.state, job.failure?.uploadFailure())

    private suspend fun rowFor(emit: FetchedJob.Emit): JobRow =
        jobRowOf(emit.destinationPath, setup.record.entryForDestination(emit.destinationPath)?.key)

    private suspend fun acknowledge(job: OsUploadJob) {
        val answer = queue.acknowledge(job)
        if (answer is ChangeOutcome.Refused) {
            log.w { "acknowledge refused (code=${answer.code} ${answer.detail}) — the queue offers the job again" }
        }
    }
}

/** One pass's jobs that were not the cycle's business, reported once rather than per job. */
private class Report(private val log: co.touchlab.kermit.Logger, private val site: String) {
    var unrecoverable = 0
    var pruned = 0

    /**
     * Unmappable jobs at `Error` — an outcome discarded for a reason this build cannot name, which nothing else
     * reports — and pruned ones at `Info`: a photo deleted or de-selected while uploading is expected, and at `Error`
     * it would put a crash-reporting event behind each one (`changes/selection-is-the-walk`, D3).
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
