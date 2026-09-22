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
import app.snapsync.ports.TransferRecord
import app.snapsync.ports.UploadDiscovery
import app.snapsync.model.TerminalOutcome

/**
 * An operator-driven, **inspectable** [BackgroundTransfer] (capability `harness-world-model`): the
 * world's stand-in for the iOS `IosBackgroundTransfer`. It models the OS upload-job lifecycle as a
 * queue an operator drives between cycles:
 *
 * - `createJob` enqueues a PENDING job and returns `CREATED`, unless the settable [jobLimit] in-flight
 *   cap is reached (`LIMIT_EXCEEDED`) or [failCreate] is set (`FAILED`).
 * - [completeJob] deposits the object key into the [store] **store-direct** (byte transfer is not
 *   routed through ktor) and moves the job to the terminal bucket, so the next `drainTerminals` records
 *   it `COMPLETED`.
 * - [failJob] moves a job to the retry bucket carrying a chosen [UploadError], driving the real engine
 *   retry chain. A first failure surfaces via `fetchRetryJobs` (the system's single free retry); a
 *   second failure of the same job returns its row to `DISCOVERED` through [drainTerminals] and is handed
 *   back for the cycle to re-create.
 *
 * Like both real adapters, this one RECORDS terminal outcomes into the [ledger] itself rather than
 * handing them up — that is the seam's contract now, and a fake that returned them instead would let a
 * green suite hide the very defect this models.
 *
 * It serves no library read. The change feed and the key resolve are [FakeUploadDiscovery]'s, bound beside
 * this queue exactly as a device root binds `IosDiscovery` beside its transport.
 */
class FakeBackgroundTransfer(
    private val store: BackendStore,
    private val ownDeviceId: String,
    /** The same ledger the composed cycle writes — this adapter records terminal outcomes into it. */
    private val ledger: TransferRecord,
) : BackgroundTransfer {

    /** Failure lever: the OS in-flight job cap. `createJob` returns `LIMIT_EXCEEDED` at/above it. */
    var jobLimit: Int = Int.MAX_VALUE

    /** Failure lever: `createJob` returns `FAILED` (a malformed destination / unusable payload). */
    var failCreate: Boolean = false

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
     * A succeeded job becomes `COMPLETED`, exactly as both adapters record it.
     */
    override suspend fun drainTerminals(): List<PlatformUploadJob> {
        val terminal = jobs.filter {
            it.state == FakeJobState.SUCCEEDED || (it.state == FakeJobState.FAILED && it.retriedOnce)
        }
        val out = mutableListOf<PlatformUploadJob>()
        for (j in terminal) {
            val succeeded = j.state == FakeJobState.SUCCEEDED
            ledger.markTerminal(j.key, if (succeeded) TerminalOutcome.COMPLETED else TerminalOutcome.FAILED)
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

    /**
     * No set, like the OS-driven queue this models: its jobs are durable, so the world runs no stranded
     * reconciliation (capability `harness-world-model`).
     */
    override suspend fun liveKeys(): Set<String>? = null

    /** No lost set either, for the same reason (capability `harness-world-model`). */
    override suspend fun lostKeys(): Set<String>? = null

    /** Nothing to drop: the fake queue keeps no per-transfer state beyond its operator-visible buckets. */
    override suspend fun discard(keys: Set<String>) = Unit

    // ---- operator actions -----------------------------------------------------------------------

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

/**
 * The world's [UploadDiscovery] (capability `harness-world-model`): the cycle's walk and key resolve over the
 * in-memory gallery, **observable** so a test can tell a cycle that walked from one that enqueued from the
 * ledger.
 *
 * Every readable walk ([discover]) is a **full enumeration** of the gallery through the real [source], as on a
 * device: there is no change feed and no token. An added asset appears in the next walk, and a removed one is
 * simply absent from it — the evidence the cycle's presence diff consumes. The operator's [makeWalkUnreadable]
 * answers the next walk the way a device answers an unreadable library.
 */
class FakeUploadDiscovery(
    private val source: CandidateSource,
    /**
     * The gallery's raw contents, unscoped — the world's stand-in for "fetch these assets by identifier".
     *
     * A thunk over [WorldGallery.current] rather than the [CandidateSource] beside it, because that seam
     * takes a policy and there is no policy to supply here: this models "fetch these assets by
     * identifier", which is what the real discovery does. The admission over ledger rows belongs to the
     * CYCLE, which applies it before it asks (capability `photo-selection-policy`) — a fake that admitted
     * here too would hide whether the cycle ever did.
     */
    private val rawAssets: () -> List<RawAsset>,
) : UploadDiscovery {

    /** Every key ever asked for, counted with repeats (see [resourcesFor]). */
    var resolvedKeyCount = 0

    private var unreadable = false

    /** Inspection: how many times the discovery feed was consumed — 0 proves a cycle enqueued from the ledger. */
    var discoverCalls = 0
        private set

    /** Inspection: every ledger key the cycle asked this fake to resolve. */
    val resolvedKeys = mutableSetOf<String>()

    /**
     * Resolve ledger keys from the world's gallery — id-scoped, and **observable**: [resolvedKeys] is
     * how a test asserts that a cycle enqueued from the ledger rather than from the discovery feed
     * (capability `sync-ledger`).
     *
     * Deliberately unscoped by the policy, unlike [discover] — as the real discovery is: this resolves the
     * keys it is handed. The cycle admits its rows against the membership's *current* policy before it gets
     * here (capability `photo-selection-policy`), so a key that reaches this fake is one the policy already
     * allowed; admitting again here would make the cycle's own admission untestable.
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

    override suspend fun discover(policy: SelectionPolicy): Discovery {
        discoverCalls++
        if (unreadable) {
            unreadable = false
            // What a device answers for a library it could not read: nothing, and NOT authoritative — so the
            // cycle deletes nothing on the strength of an empty answer (capability `sync-ledger`).
            return Discovery(candidates = emptyList(), fullEnumeration = false)
        }
        // Scoped by the POLICY exactly as far as a platform predicate scopes a device's fetch, and no further:
        // the honest source narrows by the capture floor only, leaving every other rule to the cycle's
        // admission. That matters twice over now — what comes back is also the walk's PRESENCE set, so a fake
        // that applied the whole admission would make an asset the admission excludes (a denylisted album)
        // look departed, and the cycle would delete rows a device keeps.
        return when (val read = source.candidates(policy)) {
            is CandidateRead.Readable -> Discovery(candidates = read.candidates, fullEnumeration = true)
            CandidateRead.NotReadable -> Discovery(candidates = emptyList(), fullEnumeration = false)
        }
    }

    // ---- operator actions -----------------------------------------------------------------------

    /**
     * Make the next walk unreadable: no candidates, and not authoritative — the case the cycle's deletion gate
     * exists for (capability `harness-world-model`).
     */
    fun makeWalkUnreadable() {
        unreadable = true
    }
}
