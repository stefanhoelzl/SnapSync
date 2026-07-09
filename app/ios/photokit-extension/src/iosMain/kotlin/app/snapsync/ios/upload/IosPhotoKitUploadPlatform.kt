package app.snapsync.ios.upload

import app.snapsync.engine.Resource
import app.snapsync.engine.UploadError
import app.snapsync.engine.UploadRequest
import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.upload.CreateResult
import app.snapsync.upload.Discovery
import app.snapsync.upload.PlatformJobState
import app.snapsync.upload.PlatformUploadJob
import app.snapsync.upload.UploadJobPlatform
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
import platform.Photos.PHAssetResourceUploadJobState
import platform.Photos.PHAssetResourceUploadJobStateCancelled
import platform.Photos.PHAssetResourceUploadJobStateFailed
import platform.Photos.PHAssetResourceUploadJobStateRegistered
import platform.Photos.PHAssetResourceUploadJobStateSucceeded
import platform.Photos.PHPhotoLibrary
import platform.Photos.PHPhotosErrorLimitExceeded

/**
 * The PhotoKit (iOS ≥26.1) implementation of [UploadJobPlatform] — the OS-owned upload-job queue:
 * fetch/retry/acknowledge system jobs and create jobs. Discovery, request-building, and change-token
 * archiving are identical across both upload tiers and delegated to the shared [IosDiscovery]
 * (`:app:ios:photokit-discovery`); only the job lifecycle differs and stays here. All decisions live
 * in `UploadCycle`. A returned job's ledger key is read from its **destination URL** (the last path
 * segment) — the only field reliably present for every job state (`resource` is nil for succeeded
 * jobs); the `resource`, when still available, is reused to re-create a retry-spent job. Decision-free
 * and not unit-tested (the upload-job subsystem is device-only); verified on a real device.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosPhotoKitUploadPlatform(
    private val log: Logger,
    private val discovery: IosDiscovery,
) : UploadJobPlatform {

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
            // Map the job to its ledger key via the destination URL's last path segment — the ONLY
            // field reliably present for every state. `resource` is **nil for succeeded jobs** (the
            // system releases it after upload), so it can't be the key source; keep it only as an
            // optional payload for re-creating a retry-spent job.
            val destination: NSURLRequest? = job.destination
            val key = destination?.URL?.lastPathComponent
            if (key == null) {
                // Unmappable — but EVERY presented job must be acknowledged or the system reports
                // `appex failed to acknowledge jobs for processing state` (error 50008).
                log.w { "upload job without destination URL — acknowledging to drain" }
                acknowledgeJob(job)
                continue
            }
            val resource: PHAssetResource? = job.resource
            out += PlatformUploadJob(
                key = key,
                contentType = resource?.uniformTypeIdentifier ?: "application/octet-stream",
                state = mapState(job.state),
                error = job.error?.let(::mapError),
                data = resource,
                handle = job,
            )
        }
        // (count is logged by the wrapping `platform.fetch*` invocation's exit line)
        return out
    }

    private fun actionName(action: PHAssetResourceUploadJobAction): String =
        if (action == PHAssetResourceUploadJobActionRetry) "retry" else "acknowledge"

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
            when {
                error == null -> CreateResult.CREATED
                error.code == PHPhotosErrorLimitExceeded -> {
                    log.w { "job limit exceeded — deferring remaining work this cycle" }
                    CreateResult.LIMIT_EXCEEDED
                }
                else -> {
                    // A non-limit error means the job was NOT created; surface it and return FAILED so
                    // the cycle re-creates it next discovery, rather than recording a phantom REQUESTED
                    // row for a job that never materialised.
                    log.w {
                        "createJob failed for ${request.resource.filename}: " +
                            "code=${error.code} ${error.localizedDescription}"
                    }
                    CreateResult.FAILED
                }
            }
        }
    }

    override suspend fun discoverResources(sinceToken: ByteArray?, since: String): Discovery =
        log.invocation("platform.discoverResources", result = { "${it.resources.size} resource(s)" }) {
            discovery.discover(sinceToken, since)
        }

    private fun mapState(state: PHAssetResourceUploadJobState): PlatformJobState = when (state) {
        PHAssetResourceUploadJobStateSucceeded -> PlatformJobState.SUCCEEDED
        PHAssetResourceUploadJobStateFailed -> PlatformJobState.FAILED
        PHAssetResourceUploadJobStateCancelled -> PlatformJobState.CANCELLED
        PHAssetResourceUploadJobStateRegistered -> PlatformJobState.REGISTERED
        else -> PlatformJobState.PENDING
    }

    private fun mapError(error: NSError): UploadError = UploadError.Unknown("${error.domain}:${error.code}")
}
