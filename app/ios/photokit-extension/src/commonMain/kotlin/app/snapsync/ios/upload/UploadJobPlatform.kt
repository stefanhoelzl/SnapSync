package app.snapsync.ios.upload

import app.snapsync.engine.Resource
import app.snapsync.engine.UploadError
import app.snapsync.engine.UploadRequest

/**
 * The platform seam for one background-upload cycle — everything PhotoKit-specific lives behind it.
 * The iOS implementation ([IosUploadJobPlatform]) is the only place that touches PhotoKit, so the
 * orchestration in [UploadCycle] stays pure and testable on the simulator with a fake.
 *
 * Returned system jobs are surfaced as [PlatformUploadJob]s whose [PlatformUploadJob.key] the
 * platform recomputes from the job's own asset/resource facts — the same `uploadKey` discovery uses
 * — so the cycle maps a job back to the ledger without parsing the destination URL.
 */
interface UploadJobPlatform {

    /** System jobs offered for their single `.retry` (first failures). */
    suspend fun fetchRetryJobs(): List<PlatformUploadJob>

    /** Terminal system jobs awaiting `.acknowledge` (succeeded, or retry-spent failures). */
    suspend fun fetchAckJobs(): List<PlatformUploadJob>

    /** Re-point a `.retry` job at a freshly presigned [request] (the system's single free retry). */
    suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest)

    /** Acknowledge a terminal [job], freeing its slot. */
    suspend fun acknowledge(job: PlatformUploadJob)

    /**
     * Enumerate the asset resources changed since [sinceToken] (null / expired → whole library),
     * returning them plus the cursor to persist once the cycle fully drains.
     */
    suspend fun discoverResources(sinceToken: ByteArray?): Discovery

    /** Create a system upload job for [resource] at [request]; distinguishes the in-flight cap. */
    suspend fun createJob(request: UploadRequest, resource: Resource): CreateResult
}

/** Terminal-ish state of a returned system job, mirroring `PHAssetResourceUploadJobState`. */
enum class PlatformJobState { SUCCEEDED, FAILED, CANCELLED, PENDING, REGISTERED }

/**
 * A platform-neutral view of a returned system upload job. [key] is recomputed from the job's own
 * fields (no URL parsing); [data] is the opaque `PHAssetResource` (used to rebuild an engine
 * [Resource] and to re-create a job); [handle] is the opaque underlying system job (handed back to
 * [UploadJobPlatform.retryJob] / [UploadJobPlatform.acknowledge]).
 */
class PlatformUploadJob(
    val key: String,
    val contentType: String,
    val state: PlatformJobState,
    val error: UploadError?,
    val data: Any,
    val handle: Any,
)

/** Outcome of a create attempt — [LIMIT_EXCEEDED] is the system's in-flight job cap. */
enum class CreateResult { CREATED, LIMIT_EXCEEDED }

/** Discovered resources plus the opaque cursor to persist once the cycle fully drains. */
class Discovery(val resources: List<Resource>, val nextToken: ByteArray)

/** The terminal disposition of one cycle; the Swift shell maps it to the system result. */
enum class CycleResult { COMPLETED, PROCESSING, FAILED }
