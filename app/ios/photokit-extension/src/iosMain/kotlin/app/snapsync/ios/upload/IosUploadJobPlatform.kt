package app.snapsync.ios.upload

import app.snapsync.engine.Resource
import app.snapsync.engine.UploadError
import app.snapsync.engine.UploadRequest
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
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.timeIntervalSince1970
import platform.Photos.PHAsset
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
import platform.Photos.PHFetchResult
import platform.Photos.PHObjectTypeAsset
import platform.Photos.PHPersistentChangeToken
import platform.Photos.PHPhotoLibrary
import platform.Photos.PHPhotosErrorLimitExceeded

/**
 * The PhotoKit implementation of [UploadJobPlatform] — the **only** place that touches PhotoKit, so
 * it stays as dumb as possible: fetch/retry/acknowledge system jobs, enumerate changes, and create
 * jobs. All decisions live in [UploadCycle]; key layout lives in [uploadKey]. A returned job's ledger
 * key is recomputed from its own `assetLocalIdentifier` + `resource` facts (no URL parsing), and its
 * `resource` (the `PHAssetResource`) is reused directly to re-create a retry-spent job — no asset
 * re-fetch. None of this is unit-tested (the upload-job subsystem is device-only); it is verified on a
 * real device. A [PhotoKitSmokeTest] only confirms enumeration is callable on the simulator.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosUploadJobPlatform(
    private val log: Logger,
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
            // `resource`/`assetLocalIdentifier` are ObjC-`nonnull` but can be nil at runtime for some
            // job states (capture as nullable so the runtime null-check is real, not elided). A job we
            // cannot map back to a key is skipped — never dereferenced.
            val resource: PHAssetResource? = job.resource
            val assetLocalId: String? = resource?.assetLocalIdentifier
            if (resource == null || assetLocalId == null) {
                log.w { "upload job has no resource/assetLocalIdentifier — skipping" }
                continue
            }
            out += PlatformUploadJob(
                key = uploadKey(assetLocalId.replace('/', '_'), resource.type, resource.originalFilename),
                contentType = resource.uniformTypeIdentifier,
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
        val systemJob = job.handle as PHAssetResourceUploadJob
        library.performChangesAndWait(
            changeBlock = {
                PHAssetResourceUploadJobChangeRequest.changeRequestForUploadJob(systemJob)?.acknowledge()
            },
            error = null,
        )
    }

    override suspend fun createJob(request: UploadRequest, resource: Resource): CreateResult {
        val phResource = resource.data as PHAssetResource
        val url = NSURL.URLWithString(request.url) ?: return CreateResult.CREATED
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
            if (error != null && error.code == PHPhotosErrorLimitExceeded) {
                log.w { "job limit exceeded — deferring remaining work this cycle" }
                CreateResult.LIMIT_EXCEEDED
            } else {
                CreateResult.CREATED
            }
        }
    }

    override suspend fun discoverResources(sinceToken: ByteArray?): Discovery {
        val token = sinceToken?.let(::unarchiveToken)
        val identifiers = if (token == null) {
            PHAsset.fetchAssetsWithOptions(null).localIdentifiers()
        } else {
            val changes = library.fetchPersistentChangesSinceToken(token, error = null)
            if (changes == null) {
                PHAsset.fetchAssetsWithOptions(null).localIdentifiers()
            } else {
                val identifiers = linkedSetOf<String>()
                changes.enumerateChangesWithBlock { change, _ ->
                    val details = change?.changeDetailsForObjectType(PHObjectTypeAsset, error = null)
                        ?: return@enumerateChangesWithBlock
                    details.insertedLocalIdentifiers().forEach { identifiers.add(it as String) }
                    details.updatedLocalIdentifiers().forEach { identifiers.add(it as String) }
                }
                identifiers.toList()
            }
        }
        return Discovery(resourcesFor(identifiers), archiveToken(library.currentChangeToken))
    }

    private fun resourcesFor(localIdentifiers: List<String>): List<Resource> {
        if (localIdentifiers.isEmpty()) return emptyList()
        val assets = PHAsset.fetchAssetsWithLocalIdentifiers(localIdentifiers, null)
        val resources = mutableListOf<Resource>()
        var index = 0uL
        while (index < assets.count) {
            val asset = assets.objectAtIndex(index) as PHAsset
            index++
            val assetId = asset.localIdentifier.replace('/', '_')
            val version = (asset.modificationDate?.timeIntervalSince1970 ?: 0.0).toString()
            for (any in PHAssetResource.assetResourcesForAsset(asset)) {
                val resource = any as PHAssetResource
                resources += Resource(
                    filename = uploadKey(assetId, resource.type, resource.originalFilename),
                    contentType = resource.uniformTypeIdentifier,
                    version = version,
                    metadata = emptyMap(),
                    data = resource,
                )
            }
        }
        return resources
    }

    private fun buildRequest(url: NSURL, request: UploadRequest): NSMutableURLRequest {
        val urlRequest = NSMutableURLRequest(uRL = url)
        urlRequest.setHTTPMethod("PUT")
        request.headers.forEach { (name, value) -> urlRequest.setValue(value, forHTTPHeaderField = name) }
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

    private fun PHFetchResult.localIdentifiers(): List<String> {
        val out = ArrayList<String>(count.toInt())
        var index = 0uL
        while (index < count) {
            out.add((objectAtIndex(index) as PHAsset).localIdentifier)
            index++
        }
        return out
    }
}
