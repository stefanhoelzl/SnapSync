package app.snapsync.world

import app.snapsync.model.SelectionPolicy
import app.snapsync.model.Resource
import app.snapsync.model.UploadError
import app.snapsync.model.UploadRequest
import app.snapsync.ports.CandidateSource
import app.snapsync.ports.CreateResult
import app.snapsync.ports.Discovery
import app.snapsync.ports.PlatformUploadJob
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.ports.LedgerStore
import app.snapsync.model.LedgerState

/**
 * An operator-driven, **inspectable** [BackgroundTransfer] (capability `harness-world-model`): the
 * world's stand-in for the iOS `IosBackgroundTransfer`. It models the OS upload-job lifecycle as a
 * queue an operator drives between cycles:
 *
 * - `createJob` enqueues a PENDING job and returns `CREATED`, unless the settable [jobLimit] in-flight
 *   cap is reached (`LIMIT_EXCEEDED`) or [failCreate] is set (`FAILED`).
 * - [completeJob] deposits the object key into the [store] **store-direct** (byte transfer is not
 *   routed through ktor) and moves the job to the terminal bucket, so the next `drainTerminals` records
 *   it `UPLOADED` — and the cycle's promotion pass then places, notifies and promotes it.
 * - [failJob] moves a job to the retry bucket carrying a chosen [UploadError], driving the real engine
 *   retry chain (attempt++). A first failure surfaces via `fetchRetryJobs` (the system's single free
 *   retry); a second failure of the same job is recorded `FAILED` by [drainTerminals] and handed back
 *   for the cycle to re-create.
 *
 * Like both real adapters, this one RECORDS terminal outcomes into the [ledger] itself rather than
 * handing them up — that is the seam's contract now, and a fake that returned them instead would let a
 * green suite hide the very defect this models.
 *
 * The change feed ([discoverResources]) is derived from the in-memory gallery via the real
 * [enumerator]: additions ride in `Discovery.resources`, removals in `removedAssetIds`, and an operator
 * [expireToken] returns `fullEnumeration = true` with the whole current key-set.
 */
class FakeBackgroundTransfer(
    private val store: BackendStore,
    private val ownDeviceId: String,
    private val source: CandidateSource,
    /** The same ledger the composed cycle writes — this adapter records terminal outcomes into it. */
    private val ledger: LedgerStore,
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

    /**
     * The OS job states this fake models. Private on purpose: the platform-neutral enum moved into the
     * PhotoKit adapter when terminal facts stopped crossing the port, and a test harness has no business
     * depending on one tier's technology vocabulary.
     */
    private enum class FakeJobState { PENDING, SUCCEEDED, FAILED }

    private class FakeJob(
        val key: String,
        val contentType: String,
        val data: Any,
        val handle: Int,
    ) {
        var state: FakeJobState = FakeJobState.PENDING
        var error: UploadError? = null
        var retriedOnce: Boolean = false
    }

    private fun FakeJob.view() = PlatformUploadJob(key, contentType, error, data)

    override suspend fun fetchRetryJobs(): List<PlatformUploadJob> =
        jobs.filter { it.state == FakeJobState.FAILED && !it.retriedOnce }.map { it.view() }

    /**
     * Record what the "OS" has finished, settle it, and hand back only the retry-spent failures.
     *
     * A succeeded job becomes `UPLOADED`, never `COMPLETED` — the cycle's promotion pass owes it an
     * album placement and a notify, and it finds that work by reading `UPLOADED` rows.
     */
    override suspend fun drainTerminals(): List<PlatformUploadJob> {
        val terminal = jobs.filter {
            it.state == FakeJobState.SUCCEEDED || (it.state == FakeJobState.FAILED && it.retriedOnce)
        }
        val out = mutableListOf<PlatformUploadJob>()
        for (j in terminal) {
            val succeeded = j.state == FakeJobState.SUCCEEDED
            ledger.markTerminal(j.key, if (succeeded) LedgerState.UPLOADED else LedgerState.FAILED)
            if (!succeeded) out += j.view()
        }
        jobs.removeAll(terminal) // settled with the "OS", exactly as both adapters acknowledge in place
        return out
    }

    override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) {
        // Matched by key: the seam no longer carries an opaque system handle.
        val j = jobs.firstOrNull { it.key == job.key && it.state == FakeJobState.FAILED } ?: return
        j.retriedOnce = true
        j.state = FakeJobState.PENDING // in-flight again after the single free retry
        j.error = null
    }

    override suspend fun createJob(request: UploadRequest, resource: Resource): CreateResult {
        if (failCreate) return CreateResult.FAILED
        if (jobs.size >= jobLimit) return CreateResult.LIMIT_EXCEEDED
        jobs.add(FakeJob(resource.filename, resource.contentType, resource.data, handleSeq++))
        created.add(resource)
        return CreateResult.CREATED
    }

    override suspend fun discoverResources(sinceToken: ByteArray?, policy: SelectionPolicy): Discovery {
        // Scoped by the POLICY, exactly as the PhotoKit walk is (capability `photo-selection-policy`);
        // the cycle's own admission still runs over whatever comes back.
        val current = source.candidates(policy)
        val currentAssetIds = current.mapTo(mutableSetOf()) { it.facts.assetId }
        val full = forceFull || sinceToken == null
        forceFull = false
        val nextToken = (++tokenCounter).toString().encodeToByteArray()
        return if (full) {
            knownAssetIds = currentAssetIds
            Discovery(candidates = current, nextToken = nextToken, fullEnumeration = true)
        } else {
            val added = current.filter { it.facts.assetId !in knownAssetIds }
            val removed = (knownAssetIds - currentAssetIds).toList()
            knownAssetIds = currentAssetIds
            Discovery(candidates = added, nextToken = nextToken, removedAssetIds = removed, fullEnumeration = false)
        }
    }

    // ---- operator actions -----------------------------------------------------------------------

    /** Force the next discovery to be a whole-library full enumeration (the routine token-expiry path). */
    fun expireToken() {
        forceFull = true
    }

    /** Complete a created job: deposit its object store-direct and move it to the acknowledge bucket. */
    fun completeJob(key: String) {
        val j = jobs.firstOrNull { it.key == key && it.state == FakeJobState.PENDING } ?: return
        j.state = FakeJobState.SUCCEEDED
        store.deposit(ownDeviceId, key)
    }

    /** Fail a created job with a chosen [error], driving the real retry chain next cycle. */
    fun failJob(key: String, error: UploadError) {
        val j = jobs.firstOrNull { it.key == key && it.state == FakeJobState.PENDING } ?: return
        j.state = FakeJobState.FAILED
        j.error = error
    }

    /** Inspection: the keys of every live (in-flight/terminal-unacked) job. */
    fun liveJobKeys(): List<String> = jobs.map { it.key }
}
