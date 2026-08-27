package app.snapsync.ports

import app.snapsync.model.Candidate
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.Resource
import app.snapsync.model.UploadError
import app.snapsync.model.UploadRequest

/**
 * The platform seam for one background-upload cycle — everything PhotoKit-specific lives behind it.
 * The iOS implementations (`IosPhotoKitUploadPlatform` on the OS-driven tier,
 * `IosUrlSessionUploadPlatform` on the app-driven one) are the only places that touch PhotoKit, so the
 * orchestration in [UploadCycle] stays pure and testable on the simulator with a fake.
 *
 * Returned system jobs are surfaced as [PlatformUploadJob]s whose [PlatformUploadJob.key] the
 * platform reads from the job's **destination URL** (its last path segment) — the only field
 * reliably present across the whole job lifecycle (`resource` is nil for succeeded jobs) — so the
 * cycle maps a job back to the ledger without depending on the released resource.
 */
interface BackgroundTransfer {

    /** System jobs offered for their single `.retry` (first failures). */
    suspend fun fetchRetryJobs(): List<PlatformUploadJob>

    /**
     * Record every terminal outcome the platform is holding into the ledger, settle it with the platform,
     * and return **only the jobs the cycle must still act on** — retry-spent failures whose resource is
     * still available, so the cycle can re-create them in this same cycle.
     *
     * A terminal fact never crosses this seam. The platform tells exactly one party that an upload ended,
     * and that party records it where it survives the process (`sync-ledger`'s guarded `markTerminal`);
     * handing the fact up for a later cycle to collect is what made a completed upload re-upload after
     * process death. So a succeeded job is written and acknowledged in place, and the cycle learns about
     * it by reading `UPLOADED` rows, not from this list.
     *
     * The implementation also owes the platform whatever settling it demands — the PhotoKit tier must
     * acknowledge **every** presented job or the system reports error 50008, whether or not its guarded
     * write applied.
     */
    suspend fun drainTerminals(): List<PlatformUploadJob>

    /** Re-point a `.retry` job at a freshly presigned [request] (the system's single free retry). */
    suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest)

    /**
     * Enumerate the asset resources changed since [sinceToken] (null / expired → a full enumeration),
     * returning them plus the cursor to persist once the cycle fully drains.
     *
     * [since] is the membership's capture-date cutoff (capability `photo-selection-policy`). A full enumeration
     * SHALL be scoped by it — walking the whole library costs one synchronous platform round-trip per
     * asset. An implementation MAY return assets captured before [since] (the cycle filters), but MUST NOT
     * omit any at or after it. The incremental change-token walk is already bounded by the change feed and
     * ignores [since]; the cycle filters its output the same way.
     */
    suspend fun discoverResources(sinceToken: ByteArray?, policy: SelectionPolicy): Discovery

    /**
     * Resolve ledger [keys] to uploadable [Resource]s — **id-scoped, never a walk**.
     *
     * This is what lets the ledger be the cycle's source of work (capability `sync-ledger`). A row records
     * that a resource needs uploading, but it cannot carry the platform handle `createJob` requires, so a
     * producer enqueueing from the ledger asks for exactly the keys it intends to send. A key is
     * `<assetId>-<role>.<ext>`, so an implementation has everything it needs to fetch those assets by
     * identifier and pick the matching resource.
     *
     * **Partial-tolerant, and that is the contract, not a convenience.** A key whose asset has left the
     * library resolves to nothing — the caller learns the asset departed, which is a different fact from
     * an upload failing, and the two must not be collapsed (`module-architecture`, "Absence is never
     * silent"). An implementation MUST NOT throw for a missing key and MUST NOT substitute another
     * resource for it.
     *
     * Each returned resource's `filename` is the key it was resolved for, so a caller can pair them back
     * up without a second lookup.
     */
    suspend fun resourcesFor(keys: Set<String>): List<Resource>

    /** Create a system upload job for [resource] at [request]; distinguishes the in-flight cap. */
    suspend fun createJob(request: UploadRequest, resource: Resource): CreateResult
}

/**
 * A platform-neutral view of a returned system upload job — now only ever a **retry-spent failure the
 * cycle must re-create**, since terminal facts are recorded by the platform adapter and never handed up
 * (see [BackgroundTransfer.drainTerminals]).
 *
 * [key] is recovered from the job's own destination URL (the only field reliably present across the whole
 * lifecycle — `resource` is nil for succeeded jobs). [contentType] is the type the request was created
 * with; reporting a placeholder here is not inert, because the cycle rebuilds a retried job's `Resource`
 * from the key alone and the object would be mistyped for the rest of its life. [data] is the opaque
 * `PHAssetResource`, present because a job only appears here when it can still be re-created. [error] is
 * what the platform said went wrong — carried for the engine's failure line, which is the only record of
 * why a key is being retried.
 *
 * `state` and `handle` are gone with the terminal facts: one kind of job comes back now, and the adapter
 * settles with the platform in place rather than handing a system handle up to be acknowledged later.
 */
class PlatformUploadJob(
    val key: String,
    val contentType: String,
    val error: UploadError?,
    val data: Any?,
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
    /**
     * The assets the platform returned — **candidates**, not yet admitted. Each carries cheap facts and
     * fetches its own resources on demand, so the cycle pays the per-asset round-trip only for the ones
     * its admission keeps (capability `gallery-status`).
     */
    val candidates: List<Candidate>,
    val nextToken: ByteArray,
    val removedAssetIds: List<String> = emptyList(),
    val fullEnumeration: Boolean = false,
)

/**
 * The terminal disposition of one cycle; the Swift shell maps it to the system result.
 *
 * [SKIPPED] is not a flavour of [COMPLETED]: a caller that re-arms background work must be able to tell
 * "there is nothing left to do **right now**" from "this device contributes nothing, **ever**". Collapsing
 * them re-arms a heartbeat forever on a device that will never upload. Keeping them apart is also what makes
 * every `when` over this enum a decision the compiler forces, rather than a default someone inherits.
 */
enum class CycleResult {
    /** The cycle drained: discovery is exhausted and nothing is pending. */
    COMPLETED,

    /** Work remains (cap reached / backpressure); an external trigger must re-invoke. */
    PROCESSING,

    /** The cycle failed. */
    FAILED,

    /**
     * The cycle **declined**: this membership contributes nothing (`Contribution.None` — its participation
     * direction excludes upload, capability `upload-lifecycle`). No walk, no job, no manifest, no notify, and
     * the discovery cursor is not advanced.
     *
     * Distinct from [COMPLETED] because the re-arm answer differs: a drained cycle may deserve another wake,
     * a declined one never does.
     */
    SKIPPED,
}

/**
 * The iOS 26.1 `PHBackgroundResourceUploadProcessingResult` raw value for this cycle result
 * (capability `ios-photokit-upload`; settled forcing proof ① of migration step 12). The system type
 * is **Swift-only** — declared in the SDK's swiftinterface with no ObjC header — so its
 * *construction* cannot leave the Swift shell; but it is `RawRepresentable` over `Int`, so the
 * **decision** lives here: an exhaustive, compiler-checked mapping the shell forwards verbatim via
 * `init?(rawValue:)` (`nil` → `.failure`, the same visible-retry posture the shell's former
 * `default:` arm carried). A future Kotlin case cannot slip through untaught — this `when` has no
 * `else` and stops compiling instead.
 *
 * Raw values are derived from the swiftinterface's case order (`failure`, `processing`,
 * `completed`); Session D verifies them against the SDK on device. [CycleResult.SKIPPED] maps like
 * [CycleResult.COMPLETED]: nothing to do, the system rests.
 */
fun CycleResult.processingResultRawValue(): Int = when (this) {
    CycleResult.COMPLETED, CycleResult.SKIPPED -> 2
    CycleResult.PROCESSING -> 1
    CycleResult.FAILED -> 0
}

/**
 * The OS-driven tier's pending→re-invocation rule (capability `ios-photokit-upload`; drained from
 * the untested extension root at the migration finale): the OS invokes the extension lazily (on
 * library changes), not when an upload quietly finishes — so a drained cycle that returns
 * [CycleResult.COMPLETED] leaves already-succeeded jobs un-acknowledged until the next change.
 * While the ledger still has pending (in-flight) rows, answer [CycleResult.PROCESSING] to request
 * another invocation so their completions are recorded promptly; report [CycleResult.COMPLETED]
 * only once everything is uploaded (pending == 0), so the system then rests. (The OS throttles
 * re-invocation, so this polls at its cadence, not in a loop.) This tier alone needs it — it
 * cannot observe a completion while not running; the app-driven tier's pump can.
 *
 * [pending] is consulted **only** on a completed cycle (a skipped/failed/processing result already
 * carries its re-arm answer); [onRequeue] is a diagnostics hook for the debug.log line.
 */
suspend fun CycleResult.requeueWhilePending(
    pending: suspend () -> Int,
    onRequeue: (Int) -> Unit = {},
): CycleResult {
    if (this != CycleResult.COMPLETED) return this
    val open = pending()
    if (open <= 0) return this
    onRequeue(open)
    return CycleResult.PROCESSING
}
