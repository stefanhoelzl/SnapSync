package app.snapsync.ios.upload

import app.snapsync.model.Resource
import app.snapsync.model.UploadRequest
import app.snapsync.ports.CreateResult
import app.snapsync.ports.PlatformUploadJob
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.ports.TransferRecord
import app.snapsync.logging.invocation
import app.snapsync.objc.ObjCFailure
import app.snapsync.objc.checkedObjC
import app.snapsync.objc.objcBoundary
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceUploadJob
import platform.Photos.PHAssetResourceUploadJobAction
import platform.Photos.PHAssetResourceUploadJobActionAcknowledge
import platform.Photos.PHAssetResourceUploadJobActionRetry
import platform.Photos.PHAssetResourceUploadJobChangeRequest
import platform.Photos.PHPhotoLibrary

/**
 * The PhotoKit (iOS ≥26.1) implementation of [BackgroundTransfer] — the OS-owned upload-job queue:
 * fetch/retry/acknowledge system jobs and create jobs. Discovery is not this class's: the root binds the
 * shared `IosDiscovery` as the cycle's `UploadDiscovery`, and the upload request is built by the shared
 * [uploadUrlRequest]; only the job lifecycle differs and stays here. All *domain* decisions live in
 * `UploadCycle`; the branches here are technology-vocabulary mappings (job state, error class, the
 * per-job key recovery), which is exactly what an adapter may hold (spec `module-architecture`,
 * "Ports are the I/O boundary named for the need": adapters are named for the technology, placed by
 * linkage, and MAY branch on technology vocabulary). Seated in `:adapter:ios:ext-safe` at the
 * migration finale — the extension process is its only linker, and its former `:app:ios:extension`
 * seat put adapter branching inside the zero-decision shell gate's scope.
 *
 * **What is tested and what is not.** Every mapping and per-job decision now lives in
 * `PhotoKitJobMapping.kt` beside this file and is exercised by `PhotoKitJobMappingTest` — including
 * the two nil cases that shipped as bugs. What remains here is OS **effect**: `performChangesAndWait`,
 * the acknowledge/retry change requests, job creation, and the fetch loop's iteration. Those are
 * verified on a real device; a `PHAssetResourceUploadJob` has no public initializer and only ever
 * arrives from a fetch, so no host can drive this loop with synthetic jobs.
 *
 * A returned job is resolved to its ledger row by the **destination path** the ledger recorded when the
 * job was created (capability `sync-ledger`) — the destination being the only field reliably present for
 * every job state, since `resource` is nil for succeeded jobs — and it is the only route: the v1
 * last-segment fallback is retired (`changes/retire-legacy-key-fallback`). A byte-route job it does not
 * resolve is **pruned**: its row was deleted because the photo left the library or the
 * selection, so it is acknowledged, nothing is written, and it is logged at `Info`. A job whose destination
 * has no byte-route shape at all — a v1 destination included — is counted and raised at `Error`, never drained in silence. The `resource`,
 * when still available, is reused to re-create a retry-spent job. Both are captured as **nullable locals**
 * before use: cinterop declares them non-null and they are nil at runtime, and a null check against a
 * non-null-typed value may be elided
 * (`05435ff9`, `8c8dbe28`). Do not "simplify" those two locals away — see `PhotoKitJobMapping.kt`'s KDoc
 * for the full account.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosPhotoKitUploadPlatform(
    private val log: Logger,
    // This adapter RECORDS terminal outcomes, rather than handing them to the cycle to record. The OS
    // job queue here IS durable — a succeeded job stays in the `.acknowledge` set until acknowledged —
    // so this tier never had the app-driven tier's loss; recording in place keeps one state machine across
    // both tiers. It holds only the narrow [TransferRecord]: the guarded write and the destination read.
    private val ledger: TransferRecord,
) : BackgroundTransfer {

    private val library: PHPhotoLibrary get() = PHPhotoLibrary.sharedPhotoLibrary()

    override suspend fun fetchRetryJobs(): List<PlatformUploadJob> =
        log.invocation("platform.fetchRetryJobs", result = { "${it.size} job(s)" }) {
            fetch(PHAssetResourceUploadJobActionRetry)
        }

    /**
     * Record every terminal job into the ledger and acknowledge it **in place**; return only the
     * retry-spent failures whose resource is still live, so the cycle can re-create them this cycle.
     *
     * A succeeded job is recorded `COMPLETED`: nothing a completion used to trigger is still owed, so no
     * later pass reads the row again.
     *
     * **Every** presented job is acknowledged, whatever its guarded write did — a write that applies to
     * nothing (the row was pruned, or already settled) is still a job the system expects back, and
     * leaving one un-acknowledged is what makes it report `appex failed to acknowledge jobs for
     * processing state` (error 50008).
     */
    override suspend fun drainTerminals(): List<PlatformUploadJob> =
        log.invocation("platform.drainTerminals", result = { "${it.size} job(s)" }) {
            val jobs = PHAssetResourceUploadJob.fetchJobsWithAction(
                PHAssetResourceUploadJobActionAcknowledge,
                options = null,
            )
            val out = ArrayList<PlatformUploadJob>()
            var unrecoverable = 0
            var pruned = 0
            var index = 0uL
            while (index < jobs.count) {
                val job = jobs.objectAtIndex(index) as PHAssetResourceUploadJob
                index++
                // Both captured as nullable locals FIRST — cinterop declares them non-null and they are
                // nil at runtime, and a null check against a non-null-typed value may be elided.
                val destination: NSURLRequest? = job.destination
                val resource: PHAssetResource? = job.resource
                when (val classified = classifyPhotoKitJob(destination, job.state, job.error)) {
                    FetchedJob.AcknowledgeToDrain -> {
                        unrecoverable++
                    }
                    is FetchedJob.Emit -> {
                        val key = when (val row = rowFor(classified)) {
                            is JobRow.Found -> row.key
                            // The photo left the library or the selection, maybe mid-upload: answered, nothing
                            // written, nothing handed back (capability `upload-lifecycle`).
                            JobRow.Pruned -> { pruned++; acknowledgeJob(job); continue }
                            JobRow.Unmappable -> { unrecoverable++; acknowledgeJob(job); continue }
                        }
                        // The adjudication is `terminalDisposition` (beside the other per-job decisions in
                        // PhotoKitJobMapping.kt, where it is tested); this body supplies only the effect.
                        val disposition = terminalDisposition(classified.state, resourceIsLive = resource != null)
                        if (!ledger.markTerminal(key, disposition.outcome)) {
                            // Not silent: the row was not REQUESTED — already settled, or pruned.
                            log.i { "terminal $key -> ${disposition.outcome} applied to no row" }
                        }
                        // Only a retry-spent failure that can still be re-created is the cycle's business.
                        if (disposition.reCreate) {
                            out += PlatformUploadJob(
                                key = key,
                                contentType = photoKitContentType(destination, resource),
                                error = classified.error,
                                data = resource,
                            )
                        }
                    }
                }
                acknowledgeJob(job)
            }
            reportUnrecoverable(unrecoverable, "drainTerminals")
            reportPruned(pruned, "drainTerminals")
            out
        }

    private suspend fun fetch(action: PHAssetResourceUploadJobAction): List<PlatformUploadJob> {
        val jobs = PHAssetResourceUploadJob.fetchJobsWithAction(action, options = null)
        val out = ArrayList<PlatformUploadJob>(jobs.count.toInt())
        var unrecoverable = 0
        var pruned = 0
        var index = 0uL
        while (index < jobs.count) {
            val job = jobs.objectAtIndex(index) as PHAssetResourceUploadJob
            index++
            // Capture both ObjC-nonnull-but-nilable values as nullable locals FIRST, so the runtime
            // null checks below are real rather than elided (see the class KDoc).
            val destination: NSURLRequest? = job.destination
            val resource: PHAssetResource? = job.resource
            when (val classified = classifyPhotoKitJob(destination, job.state, job.error)) {
                FetchedJob.AcknowledgeToDrain -> {
                    // Unmappable — but EVERY presented job must be acknowledged or the system reports
                    // `appex failed to acknowledge jobs for processing state` (error 50008).
                    unrecoverable++
                    acknowledgeJob(job)
                }
                is FetchedJob.Emit -> when (val row = rowFor(classified)) {
                    is JobRow.Found -> out += PlatformUploadJob(
                        key = row.key,
                        contentType = photoKitContentType(destination, resource),
                        error = classified.error,
                        data = resource,
                    )
                    // Never handed to the cycle, which would decline to retry it and leave it un-acknowledged:
                    // a job for a photo that left is answered HERE (capability `upload-lifecycle`).
                    JobRow.Pruned -> { pruned++; acknowledgeJob(job) }
                    JobRow.Unmappable -> { unrecoverable++; acknowledgeJob(job) }
                }
            }
        }
        reportUnrecoverable(unrecoverable, "fetch")
        reportPruned(pruned, "fetch")
        // (count is logged by the wrapping `platform.fetch*` invocation's exit line)
        return out
    }

    /**
     * The ledger row a returned job belongs to, or null when none is found.
     *
     * The destination this job was addressed to is what the ledger recorded when the job was created
     * (capability `sync-ledger`), so it is the route — the only one.
     */
    private suspend fun resolveKey(emit: FetchedJob.Emit): String? = (rowFor(emit) as? JobRow.Found)?.key

    /**
     * [resolveKey]'s full answer: the row by recorded destination, else pruned or unmappable ([jobRowOf]
     * decides, and is tested).
     */
    private suspend fun rowFor(emit: FetchedJob.Emit): JobRow =
        jobRowOf(emit.destinationPath, ledger.entryForDestination(emit.destinationPath)?.key)

    /**
     * Report jobs whose destination this build cannot map at all — at `Error`, so it reaches crash reporting.
     *
     * An unmappable job is an upload whose outcome is being discarded for a reason this build cannot name: no
     * destination, or one of no byte-route shape it ever created. Nothing else reports it (`module-architecture`,
     * "Absence is never silent"), and a per-job warning would be a breadcrumb rather than an event, so the count
     * is raised once per cycle and only when it is non-zero.
     *
     * A byte-route job whose row is simply gone is NOT this: see [reportPruned].
     */
    private fun reportUnrecoverable(count: Int, site: String) {
        if (count == 0) return
        log.e {
            "$site: $count upload job(s) carried no destination this build can map — their outcomes are " +
                "discarded; nothing else will report this"
        }
    }

    /**
     * Report jobs answered as **pruned** — their row was deleted by an authoritative walk because the photo left
     * the library or the selection, possibly mid-upload — at `Info`. Expected, not a fault: raising these at
     * `Error` would put a crash-reporting event behind every photo deleted or de-selected while uploading
     * (decision record `changes/selection-is-the-walk`, D3).
     */
    private fun reportPruned(count: Int, site: String) {
        if (count == 0) return
        log.i { "$site: $count upload job(s) belong to rows the walk removed — acknowledged, nothing written" }
    }

    private fun acknowledgeJob(job: PHAssetResourceUploadJob) {
        checkedObjC("acknowledgeJob") { error ->
            library.performChangesAndWait(
                changeBlock = {
                    objcBoundary(log, "acknowledgeJob.changeBlock") {
                        PHAssetResourceUploadJobChangeRequest.changeRequestForUploadJob(job)?.acknowledge()
                    }
                },
                error = error,
            )
        }.onFailure { log.w(it) { "acknowledge refused — the OS offers the job again next fetch" } }
    }

    override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) =
        log.invocation("platform.retryJob", params = "key=${job.key}") {
            // The system job is looked up again rather than carried on [PlatformUploadJob]: the seam no
            // longer passes an opaque handle, because the only other thing that needed one — the
            // acknowledge — now happens inside the drain, next to the fetch that produced it.
            val systemJob = retryJobFor(job.key) ?: run {
                log.w { "retryJob: no live .retry job for ${job.key} — it settled underneath us" }
                return@invocation
            }
            val url = NSURL.URLWithString(request.url) ?: return@invocation
            val urlRequest = uploadUrlRequest(url, request)
            checkedObjC("retryJob") { error ->
                library.performChangesAndWait(
                    changeBlock = {
                        objcBoundary(log, "retryJob.changeBlock") {
                            PHAssetResourceUploadJobChangeRequest.changeRequestForUploadJob(systemJob)
                                ?.retryWithDestination(urlRequest)
                        }
                    },
                    error = error,
                )
            }.onFailure { log.w(it) { "retryJob: the retry was refused for ${job.key}" } }
        }

    /**
     * The system job currently offered for `.retry` whose destination resolves to [key] — by the SAME route
     * the drain resolves rows by ([resolveKey]: the recorded destination path), so a retry and a drain can
     * never disagree about which row a job belongs to.
     *
     * It used to compare the destination's last path segment to the key, which under the identity-in-path
     * byte route is the resource's ROLE and matches no key: every free retry found nothing, the OS spent it,
     * and the job came back only once its retry was gone. The selection is [retryJobMatching], beside the
     * other per-job decisions in `PhotoKitJobMapping.kt`, where it is tested.
     */
    private suspend fun retryJobFor(key: String): PHAssetResourceUploadJob? {
        val jobs = PHAssetResourceUploadJob.fetchJobsWithAction(PHAssetResourceUploadJobActionRetry, options = null)
        val candidates = ArrayList<Pair<PHAssetResourceUploadJob, FetchedJob>>(jobs.count.toInt())
        var index = 0uL
        while (index < jobs.count) {
            val job = jobs.objectAtIndex(index) as PHAssetResourceUploadJob
            index++
            // Captured as a nullable local FIRST — cinterop declares it non-null and it is nil at runtime
            // (see the class KDoc).
            val destination: NSURLRequest? = job.destination
            candidates += job to classifyPhotoKitJob(destination, job.state, job.error)
        }
        return retryJobMatching(candidates, key, ::resolveKey)
    }

    override suspend fun createJob(request: UploadRequest, resource: Resource): CreateResult =
        log.invocation("platform.createJob", params = "key=${request.resource.filename}", result = { "$it" }) {
        val phResource = resource.data as? PHAssetResource ?: run {
            log.w { "createJob: resource payload is not a PHAssetResource — not creating" }
            return@invocation CreateResult.FAILED
        }
        val url = NSURL.URLWithString(request.url) ?: run {
            log.w { "createJob: malformed destination URL — not creating" }
            return@invocation CreateResult.FAILED
        }
        val urlRequest = uploadUrlRequest(url, request)
        run {
            val error = checkedObjC("createJob") { errorPtr ->
                library.performChangesAndWait(
                    changeBlock = {
                        objcBoundary(log, "createJob.changeBlock") {
                            PHAssetResourceUploadJobChangeRequest.creationRequestForJobWithDestination(urlRequest, phResource)
                        }
                    },
                    error = errorPtr,
                )
            }.exceptionOrNull() as ObjCFailure?
            // A refusal that names no code is still a refusal: it maps to FAILED, never to CREATED.
            createResultFor(error?.let { it.code ?: UNCODED_REFUSAL }).also { result ->
                when (result) {
                    CreateResult.CREATED -> Unit
                    CreateResult.LIMIT_EXCEEDED ->
                        log.w { "job limit exceeded — deferring remaining work this cycle" }
                    // A non-limit error means the job was NOT created; surface it so the cycle
                    // re-creates it next discovery, rather than recording a phantom REQUESTED row for
                    // a job that never materialised.
                    CreateResult.FAILED -> log.w {
                        "createJob failed for ${request.resource.filename}: " +
                            "code=${error?.code} ${error?.description}"
                    }
                }
            }
        }
    }
}

/** The code a refusal without an `NSError` maps to — never `null`, which [createResultFor] reads as created. */
private const val UNCODED_REFUSAL = -1L
