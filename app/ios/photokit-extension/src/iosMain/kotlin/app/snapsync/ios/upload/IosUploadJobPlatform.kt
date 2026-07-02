package app.snapsync.ios.upload

import app.snapsync.engine.Resource
import app.snapsync.engine.UploadError
import app.snapsync.engine.UploadRequest
import app.snapsync.gallery.GalleryResourceEnumerator
import app.snapsync.upload.CreateResult
import app.snapsync.upload.Discovery
import app.snapsync.upload.PlatformJobState
import app.snapsync.upload.PlatformUploadJob
import app.snapsync.upload.UploadJobPlatform
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Foundation.NSKeyedArchiver
import platform.Foundation.NSKeyedUnarchiver
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
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
import platform.Photos.PHObjectTypeAsset
import platform.Photos.PHPersistentChangeToken
import platform.Photos.PHPhotoLibrary
import platform.Photos.PHPhotosErrorLimitExceeded

/**
 * The PhotoKit implementation of [UploadJobPlatform] — the **only** place that touches PhotoKit, so
 * it stays as dumb as possible: fetch/retry/acknowledge system jobs, enumerate changes, and create
 * jobs. All decisions live in [UploadCycle]; resource enumeration + key/version layout live in the
 * shared `:domain:gallery` [GalleryResourceEnumerator] (so the producer and the re-join seed never
 * diverge). A returned job's ledger key is read from its **destination URL** (the last path segment)
 * — the only field reliably present for every job state (`resource` is nil for succeeded jobs); the
 * `resource`, when still available, is reused to re-create a retry-spent job. None of this is
 * unit-tested (the upload-job subsystem is device-only); it is verified on a real device. A
 * [PhotoKitSmokeTest] only confirms enumeration is callable on the simulator.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosUploadJobPlatform(
    private val log: Logger,
    private val enumerator: GalleryResourceEnumerator,
) : UploadJobPlatform {

    private val library: PHPhotoLibrary get() = PHPhotoLibrary.sharedPhotoLibrary()

    override suspend fun fetchRetryJobs(): List<PlatformUploadJob> = fetch(PHAssetResourceUploadJobActionRetry)

    override suspend fun fetchAckJobs(): List<PlatformUploadJob> = fetch(PHAssetResourceUploadJobActionAcknowledge)

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
            // Capture ObjC-`nonnull`-but-actually-nilable values as nullable locals so the runtime
            // null-checks are emitted, not optimized away (`resource` IS nil for succeeded jobs).
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
        log.i { "fetch(${actionName(action)}): ${out.size} job(s)" }
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

    override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) {
        val systemJob = job.handle as PHAssetResourceUploadJob
        val url = NSURL.URLWithString(request.url) ?: return
        val urlRequest = buildRequest(url, request)
        library.performChangesAndWait(
            changeBlock = {
                PHAssetResourceUploadJobChangeRequest.changeRequestForUploadJob(systemJob)?.retryWithDestination(urlRequest)
            },
            error = null,
        )
    }

    override suspend fun acknowledge(job: PlatformUploadJob) {
        acknowledgeJob(job.handle as PHAssetResourceUploadJob)
    }

    override suspend fun createJob(request: UploadRequest, resource: Resource): CreateResult {
        val phResource = resource.data as? PHAssetResource ?: run {
            log.w { "createJob: resource payload is not a PHAssetResource — not creating" }
            return CreateResult.FAILED
        }
        val url = NSURL.URLWithString(request.url) ?: run {
            log.w { "createJob: malformed destination URL — not creating" }
            return CreateResult.FAILED
        }
        val urlRequest = buildRequest(url, request)
        return memScoped {
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

    override suspend fun discoverResources(sinceToken: ByteArray?): Discovery {
        val token = sinceToken?.let(::unarchiveToken)
        val changes = token?.let { library.fetchPersistentChangesSinceToken(it, error = null) }
        if (token == null || changes == null) {
            // Full enumeration: `resources` is every current resource key — the live set the cycle
            // reconciles against. No change feed, so no incremental removals. Delegated to the shared
            // gallery enumerator (same derivation the re-join seed uses).
            return Discovery(
                resources = enumerator.enumerate(),
                nextToken = archiveToken(library.currentChangeToken),
                fullEnumeration = true,
            )
        }
        // Incremental: derive changed assets to (re)upload and removed assets to prune. Removed ids
        // are normalized `/`→`_` so they match the `<localId>-…` key scheme.
        val identifiers = linkedSetOf<String>()
        val removed = linkedSetOf<String>()
        changes.enumerateChangesWithBlock { change, _ ->
            val details = change?.changeDetailsForObjectType(PHObjectTypeAsset, error = null)
                ?: return@enumerateChangesWithBlock
            details.insertedLocalIdentifiers().forEach { identifiers.add(it as String) }
            details.updatedLocalIdentifiers().forEach { identifiers.add(it as String) }
            details.deletedLocalIdentifiers().forEach { removed.add((it as String).replace('/', '_')) }
        }
        return Discovery(
            resources = enumerator.resources(identifiers.toList()),
            nextToken = archiveToken(library.currentChangeToken),
            removedAssetIds = removed.toList(),
        )
    }

    private fun buildRequest(url: NSURL, request: UploadRequest): NSMutableURLRequest {
        val urlRequest = NSMutableURLRequest(uRL = url)
        urlRequest.setHTTPMethod("PUT")
        request.headers.forEach { (name, value) -> urlRequest.setValue(value, forHTTPHeaderField = name) }
        // Force HTTP/2-over-TCP: the system performs the background upload over HTTP/3 (QUIC) against
        // the public edge endpoint, but that QUIC connection never completes on real networks (it
        // hangs ~11s, cancels, and retries forever — nothing uploads), with no TCP fallback. The
        // edge only offers h2/http1.1 anyway. Opting the stored request out of HTTP/3 keeps uploads
        // on TCP, which works. (Verified on device: build 70's LAN MinIO never hit this — it had no
        // QUIC endpoint.)
        urlRequest.setAssumesHTTP3Capable(false)
        return urlRequest
    }

    private fun mapState(state: PHAssetResourceUploadJobState): PlatformJobState = when (state) {
        PHAssetResourceUploadJobStateSucceeded -> PlatformJobState.SUCCEEDED
        PHAssetResourceUploadJobStateFailed -> PlatformJobState.FAILED
        PHAssetResourceUploadJobStateCancelled -> PlatformJobState.CANCELLED
        PHAssetResourceUploadJobStateRegistered -> PlatformJobState.REGISTERED
        else -> PlatformJobState.PENDING
    }

    private fun mapError(error: NSError): UploadError = UploadError.Unknown("${error.domain}:${error.code}")

    // Token (un)archiving is best-effort efficiency only: any failure degrades to a full
    // re-enumeration (null token), which the ledger makes harmless — it must never fail the cycle.
    private fun archiveToken(token: PHPersistentChangeToken): ByteArray =

        runCatching {
            NSKeyedArchiver.archivedDataWithRootObject(token, requiringSecureCoding = true, error = null)?.toByteArray()
        }.onFailure { log.w(it) { "archiveToken failed — cursor will not advance this cycle" } }
            .getOrNull() ?: ByteArray(0)

    private fun unarchiveToken(bytes: ByteArray): PHPersistentChangeToken? =
        runCatching {
            NSKeyedUnarchiver.unarchivedObjectOfClass(PHPersistentChangeToken, bytes.toNSData(), error = null)
                as? PHPersistentChangeToken
        }.onFailure { log.w(it) { "unarchiveToken failed — re-enumerating from scratch" } }
            .getOrNull()
}
