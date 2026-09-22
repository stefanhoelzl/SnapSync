package app.snapsync.ports

import app.snapsync.model.Resource
import app.snapsync.model.UploadError
import app.snapsync.model.UploadRequest

/**
 * The transfer lifecycle of one background-upload cycle — creating, retrying and settling upload jobs.
 * The iOS implementations (`IosPhotoKitUploadPlatform` on the OS-driven tier,
 * `IosUrlSessionUploadPlatform` on the app-driven one) keep each platform's job vocabulary behind it, so the
 * orchestration in [UploadCycle] stays pure and testable on the simulator with a fake. What the cycle reads
 * from the photo library is not this seam's: that is [UploadDiscovery], bound once beside it.
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
     * process death. So a succeeded job is recorded `COMPLETED` and acknowledged in place, and nothing
     * about it reaches the cycle.
     *
     * The implementation also owes the platform whatever settling it demands — the PhotoKit tier must
     * acknowledge **every** presented job or the system reports error 50008, whether or not its guarded
     * write applied.
     */
    suspend fun drainTerminals(): List<PlatformUploadJob>

    /** Re-point a `.retry` job at a freshly presigned [request] (the system's single free retry). */
    suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest)

    /**
     * The ledger keys of the transfers this transport still holds, or `null` where it cannot enumerate them
     * (capability `ios-url-session-upload`, "The transport reports the transfers it still holds").
     *
     * A **read the cycle asks for**, never a call into the core: the cycle subtracts this set from the
     * `REQUESTED` rows and records the remainder `FAILED`, because a transfer the transport no longer holds
     * delivers no completion and nothing else will ever move its row. The decision and the write are the
     * cycle's; the transport reads no ledger state to answer.
     *
     * **`null` is an answer, not a failure** — the same shape as [fetchRetryJobs]. A transport whose queue
     * is the OS's durable job store (it exposes a `.retry` and an `.acknowledge` set, and no set of jobs still
     * in flight) has no stranded population to reconcile, and an absent set makes the cycle reconcile nothing
     * rather than treat every `REQUESTED` row as lost.
     */
    suspend fun liveKeys(): Set<String>?

    /**
     * The ledger keys of the transfers this transport **began and no longer holds** — including ones begun by
     * a process that has since died — or `null` where it cannot tell (capability `ios-url-session-upload`,
     * "The transport reports the transfers it still holds").
     *
     * What lets the cycle scope its per-cycle stranded pass to this transport's own transfers without the
     * ledger carrying an owner: a `REQUESTED` row outside this set may belong to another transport still
     * carrying it, so the per-cycle rule never touches it. Like [liveKeys] it is a read the cycle asks for, and
     * the transport reads no ledger state to answer.
     *
     * `null` is an answer, exactly as for [liveKeys]: a durable OS queue loses nothing when the process dies,
     * and an absent set makes the per-cycle rule reconcile nothing.
     */
    suspend fun lostKeys(): Set<String>?

    /**
     * Drop whatever this transport kept for the lost transfers [keys] — an **instruction the cycle issues**
     * after its stranded pass, once none of their rows can still be `REQUESTED`. The transport cannot decide
     * that moment itself: it reads no ledger state, and what it kept is the very marker that makes a lost
     * transfer findable. A transport that keeps nothing does nothing.
     */
    suspend fun discard(keys: Set<String>)

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
     * direction excludes upload), there is no membership at all, this process's engine is not the resolved
     * mechanism, or this process holds no full photo grant (capability `upload-lifecycle`). No walk and no job.
     *
     * Distinct from [COMPLETED] because the re-arm answer differs: a drained cycle may deserve another wake,
     * a declined one never does — whatever makes it eligible again is a transition, and the transition arms.
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

/**
 * One OS-driven `process()` invocation — [run] the cycle, then [requeueWhilePending] — as a function
 * that **never throws** (capability `ios-photokit-upload`). The extension root forwards its result
 * across the ObjC boundary, where a Kotlin throwable is not a failed cycle but a Kotlin/Native
 * `abort()` of the whole extension process: no result reaches the OS and nothing is reported.
 *
 * A throw from the cycle is reported through [onCycleFailed]; a throw from anything after it — the
 * [pending] ledger read, or a hook — through [onLateFailure]. Both answer [CycleResult.FAILED]: a
 * cycle whose bookkeeping could not be completed cannot claim `COMPLETED` (it may rest with jobs in
 * flight), and `PROCESSING` means "more work", not "error". The one path left unguarded is
 * [onLateFailure] itself, the last resort.
 *
 * Guarding the whole body rather than the one known read is the point: the defect was a shape — any
 * line after the cycle's own guard could abort the process — not a single bad call.
 */
suspend fun runProcessCycle(
    run: suspend () -> CycleResult,
    pending: suspend () -> Int,
    onCycleFinished: (CycleResult) -> Unit = {},
    onCycleFailed: (Throwable) -> Unit = {},
    onRequeue: (Int) -> Unit = {},
    onLateFailure: (Throwable) -> Unit = {},
): CycleResult = runCatching {
    runCatching { run() }
        .onSuccess(onCycleFinished)
        .getOrElse { onCycleFailed(it); CycleResult.FAILED }
        .requeueWhilePending(pending, onRequeue)
}.getOrElse { onLateFailure(it); CycleResult.FAILED }
