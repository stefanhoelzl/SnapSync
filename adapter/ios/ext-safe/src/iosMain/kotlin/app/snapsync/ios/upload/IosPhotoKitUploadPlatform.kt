package app.snapsync.ios.upload

import app.snapsync.model.Resource
import app.snapsync.model.UploadRequest
import app.snapsync.ports.CreateResult
import app.snapsync.ports.PlatformUploadJob
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.ports.TransferRecord
import app.snapsync.logging.invocation
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL

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
 * the two nil cases that shipped as bugs. The OS **effects** — the fetch, and the acknowledge, retry and
 * creation change requests — go through the [UploadJobApi] seam, which presents each job as plain facts. A
 * `PHAssetResourceUploadJob` has no public initializer and only ever arrives from a fetch, so no host can drive
 * this class with synthetic jobs; instead the upload extension's contract run records every seam call and iOS's
 * answer on a device, and every CI build replays that recording against this class (capability
 * `port-contracts`). What a replay cannot cover is [SystemUploadJobApi]'s conversion of a real job into facts.
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
class IosPhotoKitUploadPlatform internal constructor(
    private val log: Logger,
    // This adapter RECORDS terminal outcomes, rather than handing them to the cycle to record. The OS
    // job queue here IS durable — a succeeded job stays in the `.acknowledge` set until acknowledged —
    // so this tier never had the app-driven tier's loss; recording in place keeps one state machine across
    // both tiers. It holds only the narrow [TransferRecord]: the guarded write and the destination read.
    private val ledger: TransferRecord,
    // Every operating-system effect goes through this seam, so the upload extension's contract run can record
    // it and CI can replay it (capability `port-contracts`). Production never passes one.
    private val api: UploadJobApi,
) : BackgroundTransfer {

    /** The production adapter, over the real PhotoKit calls. */
    constructor(log: Logger, ledger: TransferRecord) : this(log, ledger, SystemUploadJobApi(log))

    override suspend fun fetchRetryJobs(): List<PlatformUploadJob> =
        log.invocation("platform.fetchRetryJobs", result = { "${it.size} job(s)" }) {
            fetch(JobSet.RETRY)
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
     *
     * The OS reports 50008 only in the system log, which no process can read, so no contract clause can observe
     * the OS's side of that obligation. What a clause does assert is this adapter's side: after a drain, no job it
     * was presented is presented again (capability `port-contracts`; the upload-job contract, recorded inside the
     * extension).
     */
    override suspend fun drainTerminals(): List<PlatformUploadJob> =
        log.invocation("platform.drainTerminals", result = { "${it.size} job(s)" }) {
            val out = ArrayList<PlatformUploadJob>()
            var unrecoverable = 0
            var pruned = 0
            for (job in api.fetch(JobSet.ACKNOWLEDGE)) {
                when (val classified = classifyFetchedJob(job.destinationPath, job.state, job.error)) {
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
                        // The OS answers no resource for a retry-spent job (measured, SE2, iOS 26.6.2), so a failure's live
                        // resource is fetched by identifier — the photo, if it is still in the library.
                        val live = job.resource?.let { LiveResource(it, job.resourceType) }
                            ?: if (classified.state == PhotoKitJobState.SUCCEEDED) null else api.liveResource(key)
                        val disposition = terminalDisposition(classified.state, resourceIsLive = live != null)
                        if (!ledger.markTerminal(key, disposition.outcome)) {
                            // Not silent: the row was not REQUESTED — already settled, or pruned.
                            log.i { "terminal $key -> ${disposition.outcome} applied to no row" }
                        }
                        // Only a retry-spent failure that can still be re-created is the cycle's business.
                        if (disposition.reCreate) {
                            out += PlatformUploadJob(
                                key = key,
                                contentType = jobContentType(job.contentTypeHeader, live?.type),
                                error = classified.error,
                                data = live?.handle,
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

    private suspend fun fetch(set: JobSet): List<PlatformUploadJob> {
        val jobs = api.fetch(set)
        val out = ArrayList<PlatformUploadJob>(jobs.size)
        var unrecoverable = 0
        var pruned = 0
        for (job in jobs) {
            when (val classified = classifyFetchedJob(job.destinationPath, job.state, job.error)) {
                FetchedJob.AcknowledgeToDrain -> {
                    // Unmappable — but EVERY presented job must be acknowledged or the system reports
                    // `appex failed to acknowledge jobs for processing state` (error 50008).
                    unrecoverable++
                    acknowledgeJob(job)
                }
                is FetchedJob.Emit -> when (val row = rowFor(classified)) {
                    is JobRow.Found -> out += PlatformUploadJob(
                        key = row.key,
                        contentType = jobContentType(job.contentTypeHeader, job.resourceType),
                        error = classified.error,
                        data = job.resource,
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

    private fun acknowledgeJob(job: UploadJobFacts) {
        val answer = api.acknowledge(job)
        if (!answer.ok) log.w { "acknowledge refused (code=${answer.code} ${answer.description}) — the OS offers the job again next fetch" }
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
            val url = NSURL.URLWithString(request.url)?.takeIf { isUploadDestination(request.url) } ?: run {
                log.w { "retryJob: malformed destination URL for ${job.key} — not retrying" }
                return@invocation
            }
            val answer = api.retry(systemJob, uploadUrlRequest(url, request))
            if (!answer.ok) log.w { "retryJob: the retry was refused for ${job.key} (code=${answer.code} ${answer.description})" }
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
    private suspend fun retryJobFor(key: String): UploadJobFacts? {
        val candidates = api.fetch(JobSet.RETRY).map { it to classifyFetchedJob(it.destinationPath, it.state, it.error) }
        return retryJobMatching(candidates, key, ::resolveKey)
    }

    override suspend fun createJob(request: UploadRequest, resource: Resource): CreateResult =
        log.invocation("platform.createJob", params = "key=${request.resource.filename}", result = { "$it" }) {
        val url = NSURL.URLWithString(request.url)?.takeIf { isUploadDestination(request.url) } ?: run {
            log.w { "createJob: malformed destination URL — not creating" }
            return@invocation CreateResult.FAILED
        }
        val answer = api.create(uploadUrlRequest(url, request), resource.data)
        // A refusal that names no code is still a refusal: it maps to FAILED, never to CREATED.
        createResultFor(if (answer.ok) null else answer.code ?: UNCODED_REFUSAL).also { result ->
            when (result) {
                CreateResult.CREATED -> Unit
                CreateResult.LIMIT_EXCEEDED ->
                    log.w { "job limit exceeded — deferring remaining work this cycle" }
                // A non-limit error means the job was NOT created; surface it so the cycle
                // re-creates it next discovery, rather than recording a phantom REQUESTED row for
                // a job that never materialised.
                CreateResult.FAILED -> log.w {
                    "createJob failed for ${request.resource.filename}: code=${answer.code} ${answer.description}"
                }
            }
        }
    }
}

/** The code a refusal without an `NSError` maps to — never `null`, which [createResultFor] reads as created. */
private const val UNCODED_REFUSAL = -1L
