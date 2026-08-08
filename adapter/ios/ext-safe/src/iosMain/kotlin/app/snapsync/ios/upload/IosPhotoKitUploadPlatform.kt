package app.snapsync.ios.upload

import app.snapsync.model.SelectionPolicy
import app.snapsync.model.Resource
import app.snapsync.model.UploadRequest
import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.ports.CreateResult
import app.snapsync.ports.Discovery
import app.snapsync.ports.PlatformUploadJob
import app.snapsync.ports.BackgroundTransfer
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
 * A returned job's ledger key is read from its **destination URL** (the last path segment) — the only
 * field reliably present for every job state (`resource` is nil for succeeded jobs); the `resource`,
 * when still available, is reused to re-create a retry-spent job. Both are captured as **nullable
 * locals** before use: cinterop declares them non-null and they are nil at runtime, and a null check
 * against a non-null-typed value may be elided (`05435ff9`, `8c8dbe28`). Do not "simplify" those two
 * locals away — see `PhotoKitJobMapping.kt`'s KDoc for the full account.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosPhotoKitUploadPlatform(
    private val log: Logger,
    private val discovery: IosDiscovery,
) : BackgroundTransfer {

    private val library: PHPhotoLibrary get() = PHPhotoLibrary.sharedPhotoLibrary()

    override suspend fun fetchRetryJobs(): List<PlatformUploadJob> =
        log.invocation("platform.fetchRetryJobs", result = { "${it.size} job(s)" }) {
            fetch(PHAssetResourceUploadJobActionRetry)
        }

    override suspend fun fetchAckJobs(): List<PlatformUploadJob> =
        log.invocation("platform.fetchAckJobs", result = { "${it.size} job(s)" }) {
            fetch(PHAssetResourceUploadJobActionAcknowledge)
        }

    private fun fetch(action: PHAssetResourceUploadJobAction): List<PlatformUploadJob> {
        val jobs = PHAssetResourceUploadJob.fetchJobsWithAction(action, options = null)
        val out = ArrayList<PlatformUploadJob>(jobs.count.toInt())
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
                    log.w { "upload job without destination URL — acknowledging to drain" }
                    acknowledgeJob(job)
                }
                is FetchedJob.Emit -> out += PlatformUploadJob(
                    key = classified.key,
                    contentType = photoKitContentType(destination, resource),
                    state = classified.state,
                    error = classified.error,
                    data = resource,
                    handle = job,
                )
            }
        }
        // (count is logged by the wrapping `platform.fetch*` invocation's exit line)
        return out
    }

    private fun acknowledgeJob(job: PHAssetResourceUploadJob) {
        library.performChangesAndWait(
            changeBlock = { PHAssetResourceUploadJobChangeRequest.changeRequestForUploadJob(job)?.acknowledge() },
            error = null,
        )
    }

    override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) =
        log.invocation("platform.retryJob", params = "key=${job.key}") {
            val systemJob = job.handle as PHAssetResourceUploadJob
            val url = NSURL.URLWithString(request.url) ?: return@invocation
            val urlRequest = discovery.buildRequest(url, request)
            library.performChangesAndWait(
                changeBlock = {
                    PHAssetResourceUploadJobChangeRequest.changeRequestForUploadJob(systemJob)?.retryWithDestination(urlRequest)
                },
                error = null,
            )
        }

    override suspend fun acknowledge(job: PlatformUploadJob) =
        log.invocation("platform.acknowledge", params = "key=${job.key}") {
            acknowledgeJob(job.handle as PHAssetResourceUploadJob)
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
}
