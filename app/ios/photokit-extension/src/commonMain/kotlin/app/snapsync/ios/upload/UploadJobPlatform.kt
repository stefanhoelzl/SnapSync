package app.snapsync.ios.upload

import app.snapsync.engine.Resource
import app.snapsync.engine.UploadRequest

/**
 * The platform seam for one background-upload cycle — everything PhotoKit-specific lives behind it.
 * The iOS implementation ([IosUploadJobPlatform]) is the only place that touches PhotoKit, so the
 * orchestration in [UploadCycle] stays pure and testable on the simulator with a fake.
 *
 * Each discovered [Resource] carries its opaque platform payload (the `PHAssetResource`) in
 * [Resource.data]; the cycle never reads it, it just hands the same instance back to [createJob].
 */
interface UploadJobPlatform {

    /**
     * Acknowledge every system upload job to drain the queue, regardless of outcome — for this
     * slice the dummy destinations never really upload, so there is nothing to complete or retry.
     */
    suspend fun drainJobs()

    /** Enumerate the asset resources changed since the last cycle, mapped to engine [Resource]s. */
    suspend fun discoverResources(): List<Resource>

    /** Create a system upload job for [resource] pointed at [request]'s (dummy) destination. */
    suspend fun createJob(request: UploadRequest, resource: Resource)
}
