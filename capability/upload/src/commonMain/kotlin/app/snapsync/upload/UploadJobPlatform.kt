package app.snapsync.upload

import app.snapsync.engine.Resource
import app.snapsync.engine.UploadError
import app.snapsync.engine.UploadRequest

/**
 * The platform seam for one background-upload cycle — everything PhotoKit-specific lives behind it.
 * The iOS implementation ([IosUploadJobPlatform]) is the only place that touches PhotoKit, so the
 * orchestration in [UploadCycle] stays pure and testable on the simulator with a fake.
 *
 * Returned system jobs are surfaced as [PlatformUploadJob]s whose [PlatformUploadJob.key] the
 * platform reads from the job's **destination URL** (its last path segment) — the only field
 * reliably present across the whole job lifecycle (`resource` is nil for succeeded jobs) — so the
 * cycle maps a job back to the ledger without depending on the released resource.
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
 * A platform-neutral view of a returned system upload job. [key] is recovered from the job's own
 * destination URL (the only field reliably present across the whole lifecycle — `resource` is nil
 * for succeeded jobs). [data] is the opaque `PHAssetResource` *when still available* (used to
 * re-create a retry-spent job; null for succeeded/released jobs). [handle] is the opaque underlying
 * system job (handed back to [UploadJobPlatform.retryJob] / [UploadJobPlatform.acknowledge]).
 */
class PlatformUploadJob(
    val key: String,
    val contentType: String,
    val state: PlatformJobState,
    val error: UploadError?,
    val data: Any?,
    val handle: Any,
)

/**
 * Outcome of a create attempt. [CREATED] → the platform job exists (record `UploadStarted`);
 * [LIMIT_EXCEEDED] → the system's in-flight job cap (defer, request re-invocation); [FAILED] → the
 * job could not be created (e.g. a malformed destination or an unusable resource payload) and was
 * NOT created, so the caller must NOT record `REQUESTED` for a job that does not exist.
 */
enum class CreateResult { CREATED, LIMIT_EXCEEDED, FAILED }

/**
 * Discovered resources plus the opaque cursor to persist once the cycle fully drains.
 *
 * [removedAssetIds] are the asset identifiers reported removed by the change feed this cycle
 * (normalized `/`→`_` to match the key scheme), used to prune their ledger rows incrementally;
 * empty on a full enumeration (the change feed isn't consulted). [fullEnumeration] is true when
 * this discovery enumerated the whole library (no/expired token), so [resources] holds **every**
 * current resource key — the live key-set the cycle reconciles the ledger against.
 */
class Discovery(
    val resources: List<Resource>,
    val nextToken: ByteArray,
    val removedAssetIds: List<String> = emptyList(),
    val fullEnumeration: Boolean = false,
)

/** The terminal disposition of one cycle; the Swift shell maps it to the system result. */
enum class CycleResult { COMPLETED, PROCESSING, FAILED }
