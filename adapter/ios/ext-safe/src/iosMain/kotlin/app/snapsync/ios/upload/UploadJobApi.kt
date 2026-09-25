@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.ios.upload

import app.snapsync.gallery.photoKitResourceRole
import app.snapsync.model.UploadError
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.denormalizeAssetId
import app.snapsync.model.roleFromUploadKey
import app.snapsync.objc.ObjCFailure
import app.snapsync.objc.checkedObjC
import app.snapsync.objc.objcBoundary
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSError
import platform.Foundation.NSURLRequest
import platform.Photos.PHAsset
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceUploadJob
import platform.Photos.PHAssetResourceUploadJobAction
import platform.Photos.PHAssetResourceUploadJobActionAcknowledge
import platform.Photos.PHAssetResourceUploadJobActionRetry
import platform.Photos.PHAssetResourceUploadJobChangeRequest
import platform.Photos.PHPhotoLibrary

/** Which set the OS presents a job in — `PHAssetResourceUploadJobAction`'s two values, named neutrally. */
internal enum class JobSet { RETRY, ACKNOWLEDGE }

/**
 * One presented job, as plain facts — everything [IosPhotoKitUploadPlatform] reads from a
 * `PHAssetResourceUploadJob`, and nothing more.
 *
 * [handle] is what a change request is made against, and [resource] is what a re-creation needs; both are opaque
 * here — the job and its `PHAssetResource` on a device, a recorded token on replay — so the adapter's logic runs
 * over facts a replay can supply, although a job object cannot be constructed. The conversion from a real job is
 * [SystemUploadJobApi]'s alone, and it is the one step a replay does not cover.
 */
internal class UploadJobFacts(
    val handle: Any,
    /** The destination's path — the ledger's route back to the row — or `null` when the OS answered no destination. */
    val destinationPath: String?,
    /** The `Content-Type` the stored destination carries, or `null`. */
    val contentTypeHeader: String?,
    val state: PhotoKitJobState,
    val error: UploadError?,
    /** The job's resource, or `null` when the OS answered none. */
    val resource: Any?,
    /** The resource's uniform type identifier, where a resource was answered. */
    val resourceType: String?,
)

/** A photo's live resource, fetched by identifier: the handle a re-created job sends, and its type. */
internal class LiveResource(val handle: Any, val type: String?)

/** What the OS answered a change request: whether it took, and if not, which code and why. */
internal class ChangeAnswer(val ok: Boolean, val code: Long?, val description: String?)

/**
 * The operating-system effects [IosPhotoKitUploadPlatform] makes, as a seam in this module (capability
 * `docs/architecture.md`, "Hosts CI cannot reach are recorded at the operating-system boundary and replayed on every
 * build"): the upload extension's contract run records every call and iOS's answer through it, and every CI build
 * replays that recording against the current adapter. Production binds [SystemUploadJobApi]; nothing else does.
 */
internal interface UploadJobApi {
    fun fetch(set: JobSet): List<UploadJobFacts>
    fun acknowledge(job: UploadJobFacts): ChangeAnswer
    fun retry(job: UploadJobFacts, destination: NSURLRequest): ChangeAnswer
    fun create(destination: NSURLRequest, resource: Any): ChangeAnswer

    /**
     * The live resource for the upload [key] — the photo's resource of the key's role — or `null` when the photo has
     * left the library. What a retry-spent job's `resource` would have been: the OS answers none for one (measured,
     * SE2, iOS 26.6.2), so re-creation fetches it by identifier instead.
     */
    fun liveResource(key: String): LiveResource?
}

/** The real PhotoKit calls. */
internal class SystemUploadJobApi(private val log: Logger) : UploadJobApi {

    private val library: PHPhotoLibrary get() = PHPhotoLibrary.sharedPhotoLibrary()

    override fun fetch(set: JobSet): List<UploadJobFacts> {
        val jobs = PHAssetResourceUploadJob.fetchJobsWithAction(set.action(), options = null)
        val out = ArrayList<UploadJobFacts>(jobs.count.toInt())
        var index = 0uL
        while (index < jobs.count) {
            val job = jobs.objectAtIndex(index) as PHAssetResourceUploadJob
            index++
            // Both captured as nullable locals FIRST — cinterop declares them non-null and they are nil at
            // runtime, and a null check against a non-null-typed value may be elided (`PhotoKitJobMapping.kt`).
            val destination: NSURLRequest? = job.destination
            val resource: PHAssetResource? = job.resource
            val error: NSError? = job.error
            out += UploadJobFacts(
                handle = job,
                destinationPath = destination?.URL?.path,
                contentTypeHeader = destination.contentTypeHeader(),
                state = photoKitJobState(job.state),
                error = error?.let(::photoKitUploadError),
                resource = resource,
                resourceType = resource?.uniformTypeIdentifier,
            )
        }
        return out
    }

    override fun acknowledge(job: UploadJobFacts): ChangeAnswer = change("acknowledgeJob") {
        PHAssetResourceUploadJobChangeRequest.changeRequestForUploadJob(job.handle as PHAssetResourceUploadJob)
            ?.acknowledge()
    }

    override fun retry(job: UploadJobFacts, destination: NSURLRequest): ChangeAnswer = change("retryJob") {
        PHAssetResourceUploadJobChangeRequest.changeRequestForUploadJob(job.handle as PHAssetResourceUploadJob)
            ?.retryWithDestination(destination)
    }

    override fun create(destination: NSURLRequest, resource: Any): ChangeAnswer {
        val phResource = resource as? PHAssetResource
            ?: return ChangeAnswer(ok = false, code = null, description = "the resource payload is not a PHAssetResource")
        return change("createJob") {
            PHAssetResourceUploadJobChangeRequest.creationRequestForJobWithDestination(destination, phResource)
        }
    }

    override fun liveResource(key: String): LiveResource? {
        val localId = denormalizeAssetId(assetIdFromUploadKey(key))
        val role = roleFromUploadKey(key)
        val asset = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(localId), null).firstObject() as? PHAsset ?: return null
        val resource = PHAssetResource.assetResourcesForAsset(asset)
            .filterIsInstance<PHAssetResource>()
            .firstOrNull { photoKitResourceRole(it.type) == role } ?: return null
        return LiveResource(resource, resource.uniformTypeIdentifier)
    }

    private fun change(name: String, request: () -> Unit): ChangeAnswer {
        val failure = checkedObjC(name) { error ->
            library.performChangesAndWait(
                changeBlock = { objcBoundary(log, "$name.changeBlock") { request() } },
                error = error,
            )
        }.exceptionOrNull() as ObjCFailure?
        return ChangeAnswer(ok = failure == null, code = failure?.code, description = failure?.description)
    }

    private fun JobSet.action(): PHAssetResourceUploadJobAction = when (this) {
        JobSet.RETRY -> PHAssetResourceUploadJobActionRetry
        JobSet.ACKNOWLEDGE -> PHAssetResourceUploadJobActionAcknowledge
    }
}
