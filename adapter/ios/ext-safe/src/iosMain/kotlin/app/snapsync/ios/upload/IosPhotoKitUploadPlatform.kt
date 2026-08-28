package app.snapsync.ios.upload

import app.snapsync.model.SelectionPolicy
import app.snapsync.model.Resource
import app.snapsync.model.UploadRequest
import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.ports.CreateResult
import app.snapsync.ports.Discovery
import app.snapsync.ports.PlatformUploadJob
import app.snapsync.model.LedgerState
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.ports.LedgerStore
import app.snapsync.logging.invocation
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSError
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
 * fetch/retry/acknowledge system jobs and create jobs. Discovery, request-building, and change-token
 * archiving are identical across both upload tiers and delegated to the shared [IosDiscovery]
 * (same module); only the job lifecycle differs and stays here. All *domain* decisions live in
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
 * every job state, since `resource` is nil for succeeded jobs. A job created by a build that predates
 * that column falls back to the destination's last path segment, which was the key under the
 * pre-identity byte shape and is null for any other; a job neither route resolves is counted and raised
 * at `Error`, never drained in silence. The `resource`, when still available, is reused to re-create a
 * retry-spent job. Both are captured as **nullable locals** before use: cinterop declares them non-null
 * and they are nil at runtime, and a null check against a non-null-typed value may be elided
 * (`05435ff9`, `8c8dbe28`). Do not "simplify" those two locals away — see `PhotoKitJobMapping.kt`'s KDoc
 * for the full account.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosPhotoKitUploadPlatform(
    private val log: Logger,
    private val discovery: IosDiscovery,
    // This adapter RECORDS terminal outcomes, rather than handing them to the cycle to record. The OS
    // job queue here IS durable — a succeeded job stays in the `.acknowledge` set until acknowledged —
    // so this tier never had the app-driven tier's loss. What it gains is one state machine across both:
    // the cycle's promotion pass is the single place album placement and the notify fire from.
    private val ledger: LedgerStore,
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
     * A succeeded job becomes `UPLOADED`, not `COMPLETED`: the cycle's promotion pass owes it an
     * event-album placement and a completion notify, and it finds that work by reading `UPLOADED` rows.
     * Writing `COMPLETED` here would present the pass with an already-settled row and skip both.
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
                        val key = resolveKey(classified)
                        if (key == null) {
                            unrecoverable++
                            acknowledgeJob(job)
                            continue
                        }
                        // The adjudication is `terminalDisposition` (beside the other per-job decisions in
                        // PhotoKitJobMapping.kt, where it is tested); this body supplies only the effect.
                        val disposition = terminalDisposition(classified.state, resourceIsLive = resource != null)
                        if (!ledger.markTerminal(key, disposition.ledgerState)) {
                            // Not silent: the row was not REQUESTED — already settled, or pruned.
                            log.i { "terminal $key -> ${disposition.ledgerState} applied to no row" }
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
            out
        }

    private suspend fun fetch(action: PHAssetResourceUploadJobAction): List<PlatformUploadJob> {
        val jobs = PHAssetResourceUploadJob.fetchJobsWithAction(action, options = null)
        val out = ArrayList<PlatformUploadJob>(jobs.count.toInt())
        var unrecoverable = 0
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
                is FetchedJob.Emit -> {
                    val key = resolveKey(classified)
                    if (key == null) {
                        unrecoverable++
                        acknowledgeJob(job)
                    } else {
                        out += PlatformUploadJob(
                            key = key,
                            contentType = photoKitContentType(destination, resource),
                            error = classified.error,
                            data = resource,
                        )
                    }
                }
            }
        }
        reportUnrecoverable(unrecoverable, "fetch")
        // (count is logged by the wrapping `platform.fetch*` invocation's exit line)
        return out
    }

    /**
     * The ledger row a returned job belongs to, or null when neither route finds one.
     *
     * The destination this job was addressed to is what the ledger recorded when the job was created
     * (capability `sync-ledger`), so it is the primary route. The fallback is the pre-identity shape's
     * last path segment, which was the key there — correct for a job created by the outgoing build and
     * for nothing else, which is why the classifier yields it only for that shape.
     */
    private suspend fun resolveKey(emit: FetchedJob.Emit): String? =
        ledger.entryForDestination(emit.destinationPath)?.key ?: emit.legacyKey

    /**
     * Report jobs this cycle could not resolve to a row — at `Error`, so it reaches crash reporting.
     *
     * An unrecoverable job is an upload whose outcome is being discarded: its row stays `REQUESTED`,
     * which no routine path clears, so the resource is never promoted, never enters the manifest, and is
     * never visible to another member — while its bytes sit on the backend. Nothing else reports it
     * (`module-architecture`, "Absence is never silent"), and a per-job warning would be a breadcrumb
     * rather than an event, so the count is raised once per cycle and only when it is non-zero.
     */
    private fun reportUnrecoverable(count: Int, site: String) {
        if (count == 0) return
        log.e {
            "$site: $count upload job(s) could not be resolved to a ledger row — their outcomes are " +
                "discarded and those rows stay REQUESTED; nothing else will report this"
        }
    }

    private fun acknowledgeJob(job: PHAssetResourceUploadJob) {
        library.performChangesAndWait(
            changeBlock = { PHAssetResourceUploadJobChangeRequest.changeRequestForUploadJob(job)?.acknowledge() },
            error = null,
        )
    }

    override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) =
        log.invocation("platform.retryJob", params = "key=${job.key}") {
            // The system job is looked up again rather than carried on [PlatformUploadJob]: the seam no
            // longer passes an opaque handle, because the only other thing that needed one — the
            // acknowledge — now happens inside the drain, next to the fetch that produced it.
            val systemJob = jobWithKey(PHAssetResourceUploadJobActionRetry, job.key) ?: run {
                log.w { "retryJob: no live .retry job for ${job.key} — it settled underneath us" }
                return@invocation
            }
            val url = NSURL.URLWithString(request.url) ?: return@invocation
            val urlRequest = discovery.buildRequest(url, request)
            library.performChangesAndWait(
                changeBlock = {
                    PHAssetResourceUploadJobChangeRequest.changeRequestForUploadJob(systemJob)?.retryWithDestination(urlRequest)
                },
                error = null,
            )
        }

    /** The system job currently offered for [action] whose destination names [key], if it is still there. */
    private fun jobWithKey(action: PHAssetResourceUploadJobAction, key: String): PHAssetResourceUploadJob? {
        val jobs = PHAssetResourceUploadJob.fetchJobsWithAction(action, options = null)
        var index = 0uL
        while (index < jobs.count) {
            val job = jobs.objectAtIndex(index) as PHAssetResourceUploadJob
            index++
            val destination: NSURLRequest? = job.destination
            if (destination?.URL?.lastPathComponent == key) return job
        }
        return null
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
        val urlRequest = discovery.buildRequest(url, request)
        memScoped {
            val errorVar = alloc<ObjCObjectVar<NSError?>>()
            library.performChangesAndWait(
                changeBlock = {
                    PHAssetResourceUploadJobChangeRequest.creationRequestForJobWithDestination(urlRequest, phResource)
                },
                error = errorVar.ptr,
            )
            val error = errorVar.value
            createResultFor(error?.code).also { result ->
                when (result) {
                    CreateResult.CREATED -> Unit
                    CreateResult.LIMIT_EXCEEDED ->
                        log.w { "job limit exceeded — deferring remaining work this cycle" }
                    // A non-limit error means the job was NOT created; surface it so the cycle
                    // re-creates it next discovery, rather than recording a phantom REQUESTED row for
                    // a job that never materialised.
                    CreateResult.FAILED -> log.w {
                        "createJob failed for ${request.resource.filename}: " +
                            "code=${error?.code} ${error?.localizedDescription}"
                    }
                }
            }
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
}
