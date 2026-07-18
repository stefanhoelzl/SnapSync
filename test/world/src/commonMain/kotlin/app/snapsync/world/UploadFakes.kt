package app.snapsync.world

import app.snapsync.model.Resource
import app.snapsync.model.UploadError
import app.snapsync.model.UploadRequest
import app.snapsync.ports.PhotoLibrary
import app.snapsync.ports.CreateResult
import app.snapsync.ports.Discovery
import app.snapsync.ports.PlatformJobState
import app.snapsync.ports.PlatformUploadJob
import app.snapsync.ports.BackgroundTransfer

/**
 * An operator-driven, **inspectable** [BackgroundTransfer] (capability `harness-world-model`): the
 * world's stand-in for the iOS `IosBackgroundTransfer`. It models the OS upload-job lifecycle as a
 * queue an operator drives between cycles:
 *
 * - `createJob` enqueues a PENDING job and returns `CREATED`, unless the settable [jobLimit] in-flight
 *   cap is reached (`LIMIT_EXCEEDED`) or [failCreate] is set (`FAILED`).
 * - [completeJob] deposits the object key into the [store] **store-direct** (byte transfer is not
 *   routed through ktor) and moves the job to the acknowledge bucket, so the next cycle records it
 *   `COMPLETED`.
 * - [failJob] moves a job to the retry bucket carrying a chosen [UploadError], driving the real engine
 *   retry chain (attempt++). A first failure surfaces via `fetchRetryJobs` (the system's single free
 *   retry); a second failure of the same job surfaces via `fetchAckJobs` as retry-spent, where the
 *   cycle re-creates a fresh job.
 *
 * The change feed ([discoverResources]) is derived from the in-memory gallery via the real
 * [enumerator]: additions ride in `Discovery.resources`, removals in `removedAssetIds`, and an operator
 * [expireToken] returns `fullEnumeration = true` with the whole current key-set.
 */
class FakeBackgroundTransfer(
    private val store: BackendStore,
    private val ownDeviceId: String,
    private val enumerator: PhotoLibrary,
) : BackgroundTransfer {

    /** Failure lever: the OS in-flight job cap. `createJob` returns `LIMIT_EXCEEDED` at/above it. */
    var jobLimit: Int = Int.MAX_VALUE

    /** Failure lever: `createJob` returns `FAILED` (a malformed destination / unusable payload). */
    var failCreate: Boolean = false

    private var tokenCounter = 0
    private var forceFull = false
    private var knownAssetIds: Set<String> = emptySet()

    private var handleSeq = 0
    private val jobs = mutableListOf<FakeJob>()

    /** Inspection: every resource a job was created for (retry chains visible via repeated keys). */
    val created = mutableListOf<Resource>()

    private class FakeJob(
        val key: String,
        val contentType: String,
        val data: Any,
        val handle: Int,
    ) {
        var state: PlatformJobState = PlatformJobState.PENDING
        var error: UploadError? = null
        var retriedOnce: Boolean = false
    }

    private fun FakeJob.view() = PlatformUploadJob(key, contentType, state, error, data, handle)

    override suspend fun fetchRetryJobs(): List<PlatformUploadJob> =
        jobs.filter { it.state == PlatformJobState.FAILED && !it.retriedOnce }.map { it.view() }

    override suspend fun fetchAckJobs(): List<PlatformUploadJob> =
        jobs.filter {
            it.state == PlatformJobState.SUCCEEDED ||
                (it.state == PlatformJobState.FAILED && it.retriedOnce)
        }.map { it.view() }

    override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) {
        val j = jobs.firstOrNull { it.handle == job.handle } ?: return
        j.retriedOnce = true
        j.state = PlatformJobState.PENDING // in-flight again after the single free retry
        j.error = null
    }

    override suspend fun acknowledge(job: PlatformUploadJob) {
        jobs.removeAll { it.handle == job.handle }
    }

    override suspend fun createJob(request: UploadRequest, resource: Resource): CreateResult {
        if (failCreate) return CreateResult.FAILED
        if (jobs.size >= jobLimit) return CreateResult.LIMIT_EXCEEDED
        jobs.add(FakeJob(resource.filename, resource.contentType, resource.data, handleSeq++))
        created.add(resource)
        return CreateResult.CREATED
    }

    override suspend fun discoverResources(sinceToken: ByteArray?, since: String): Discovery {
        // The full enumeration is scoped by the membership's cutoff, exactly as the PhotoKit walk is
        // (capability `photo-selection-policy`); the cycle's own filter still runs over what comes back.
        val current = enumerator.enumerate(since)
        val currentAssetIds = current.mapTo(mutableSetOf()) { it.assetId }
        val full = forceFull || sinceToken == null
        forceFull = false
        val nextToken = (++tokenCounter).toString().encodeToByteArray()
        return if (full) {
            knownAssetIds = currentAssetIds
            Discovery(resources = current, nextToken = nextToken, fullEnumeration = true)
        } else {
            val added = current.filter { it.assetId !in knownAssetIds }
            val removed = (knownAssetIds - currentAssetIds).toList()
            knownAssetIds = currentAssetIds
            Discovery(resources = added, nextToken = nextToken, removedAssetIds = removed, fullEnumeration = false)
        }
    }

    // ---- operator actions -----------------------------------------------------------------------

    /** Force the next discovery to be a whole-library full enumeration (the routine token-expiry path). */
    fun expireToken() {
        forceFull = true
    }

    /** Complete a created job: deposit its object store-direct and move it to the acknowledge bucket. */
    fun completeJob(key: String) {
        val j = jobs.firstOrNull { it.key == key && it.state == PlatformJobState.PENDING } ?: return
        j.state = PlatformJobState.SUCCEEDED
        store.deposit(ownDeviceId, key)
    }

    /** Fail a created job with a chosen [error], driving the real retry chain next cycle. */
    fun failJob(key: String, error: UploadError) {
        val j = jobs.firstOrNull { it.key == key && it.state == PlatformJobState.PENDING } ?: return
        j.state = PlatformJobState.FAILED
        j.error = error
    }

    /** Inspection: the keys of every live (in-flight/terminal-unacked) job. */
    fun liveJobKeys(): List<String> = jobs.map { it.key }
}
