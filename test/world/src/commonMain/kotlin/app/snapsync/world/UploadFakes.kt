package app.snapsync.world

import app.snapsync.model.SelectionPolicy
import app.snapsync.model.RawAsset
import app.snapsync.model.Resource
import app.snapsync.model.resourcesFrom
import app.snapsync.model.UploadError
import app.snapsync.model.UploadRequest
import app.snapsync.ports.CandidateSource
import app.snapsync.model.CandidateRead
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
    /**
     * The gallery's raw contents, unscoped — the world's stand-in for "fetch these assets by identifier".
     *
     * A thunk over [WorldGallery.current] rather than the [CandidateSource] beside it, because that seam
     * takes a policy and there is no policy to supply here: this models "fetch these assets by
     * identifier", which is what the real adapters do. The admission over ledger rows belongs to the
     * CYCLE, which applies it before it asks (capability `photo-selection-policy`) — a fake that admitted
     * here too would hide whether the cycle ever did.
     */
    private val rawAssets: () -> List<RawAsset>,
) : BackgroundTransfer {

    /** Every key ever asked for, counted with repeats (see [resourcesFor]). */
    var resolvedKeyCount = 0

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

    /** Inspection: how many times the discovery feed was consumed — 0 proves a cycle enqueued from the ledger. */
    var discoverCalls = 0
        private set

    /** Inspection: every ledger key the cycle asked this fake to resolve. */
    val resolvedKeys = mutableSetOf<String>()

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

    /**
     * Derived from [jobLimit] — the same number [createJob] admits against — so the world cannot report a
     * capacity it would then refuse, exactly as the device adapter derives its answer from its own cap.
     *
     * An unset [jobLimit] reports **no number**, not an enormous one. `Int.MAX_VALUE` is this fake's way of
     * saying "the operator has configured no cap", and that is the same fact the OS-driven tier reports
     * with `null`: there is no meaningful ceiling to give. It also keeps every test that never touches the
     * lever on the cycle's own batch bound, so setting a limit is the only thing that changes a read.
     */
    override suspend fun remainingCapacity(): Int? =
        if (jobLimit == Int.MAX_VALUE) null else (jobLimit - jobs.size).coerceAtLeast(0)

    override suspend fun createJob(request: UploadRequest, resource: Resource): CreateResult {
        if (failCreate) return CreateResult.FAILED
        if (jobs.size >= jobLimit) return CreateResult.LIMIT_EXCEEDED
        jobs.add(FakeJob(resource.filename, resource.contentType, resource.data, handleSeq++))
        created.add(resource)
        return CreateResult.CREATED
    }

    /**
     * Resolve ledger keys from the world's gallery — id-scoped, and **observable**: [resolvedKeys] is
     * how a test asserts that a cycle enqueued from the ledger rather than from the discovery feed
     * (capability `sync-ledger`).
     *
     * Deliberately unscoped by the policy, unlike [discoverResources] — as the real adapters are: this
     * resolves the keys it is handed. The cycle admits its rows against the membership's *current* policy
     * before it gets here (capability `photo-selection-policy`), so a key that reaches this fake is one
     * the policy already allowed; admitting again here would make the cycle's own admission untestable.
     *
     * An asset the operator removed from the gallery resolves to nothing — the port's partial contract,
     * and the case a test needs in order to construct "the asset left between the row and the send".
     */
    override suspend fun resourcesFor(keys: Set<String>): List<Resource> {
        resolvedKeys += keys
        // How many keys were resolved in total, not how many distinct ones — the surplus this bound exists
        // to remove is repeated work on rows the platform was never going to take, and a set hides it.
        resolvedKeyCount += keys.size
        return resourcesFrom(rawAssets()).filter { it.filename in keys }
    }

    override suspend fun discoverResources(sinceToken: ByteArray?, policy: SelectionPolicy): Discovery {
        discoverCalls++
        // Scoped by the POLICY, exactly as the PhotoKit walk is (capability `photo-selection-policy`);
        // the cycle's own admission still runs over whatever comes back.
        // The world's source is the honest in-memory fake, which always has an answer; `NotReadable`
        // would mean the operator's failure lever fired, and the same rule the device holds applies —
        // enumerate nothing and KEEP the cursor, so an un-read cycle costs an idle pass, not a photo.
        val current = when (val read = source.candidates(policy)) {
            is CandidateRead.Readable -> read.candidates
            CandidateRead.NotReadable -> return Discovery(
                candidates = emptyList(),
                nextToken = sinceToken ?: ByteArray(0),
                fullEnumeration = false,
            )
        }
        // Removals are diffed against the LIBRARY, unscoped — never against the policy-scoped read above.
        // The real feed's removals are PhotoKit's `deletedLocalIdentifiers`: assets that left the library.
        // Diffing the scoped set instead would report a *narrowing reconfigure* as a mass deletion, and
        // the cycle would mark those rows absent — silently doing, in the harness only, the job the
        // enqueue admission does on a device, and hiding whether the admission happens at all.
        val presentAssetIds = rawAssets().mapTo(mutableSetOf()) { it.assetId }
        val full = forceFull || sinceToken == null
        forceFull = false
        val nextToken = (++tokenCounter).toString().encodeToByteArray()
        return if (full) {
            knownAssetIds = presentAssetIds
            Discovery(candidates = current, nextToken = nextToken, fullEnumeration = true)
        } else {
            val added = current.filter { it.facts.assetId !in knownAssetIds }
            val removed = (knownAssetIds - presentAssetIds).toList()
            knownAssetIds = presentAssetIds
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
