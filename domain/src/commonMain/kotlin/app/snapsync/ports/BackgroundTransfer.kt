package app.snapsync.ports

import app.snapsync.model.Resource
import app.snapsync.model.UploadError
import app.snapsync.model.UploadRequest

/**
 * The platform seam for one background-upload cycle — everything PhotoKit-specific lives behind it.
 * The iOS implementation ([IosBackgroundTransfer]) is the only place that touches PhotoKit, so the
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

    /** Terminal system jobs awaiting `.acknowledge` (succeeded, or retry-spent failures). */
    suspend fun fetchAckJobs(): List<PlatformUploadJob>

    /** Re-point a `.retry` job at a freshly presigned [request] (the system's single free retry). */
    suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest)

    /** Acknowledge a terminal [job], freeing its slot. */
    suspend fun acknowledge(job: PlatformUploadJob)

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
    suspend fun discoverResources(sinceToken: ByteArray?, since: String): Discovery

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
 * system job (handed back to [BackgroundTransfer.retryJob] / [BackgroundTransfer.acknowledge]).
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
