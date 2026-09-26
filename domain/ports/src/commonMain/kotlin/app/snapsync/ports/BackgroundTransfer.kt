package app.snapsync.ports

import app.snapsync.model.Resource
import app.snapsync.model.UploadError
import app.snapsync.model.UploadRequest
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.CycleResult

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
     * and that party records it where it survives the process (`photo-sharing`'s guarded `markTerminal`);
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

    /** Create a system upload job for [resource] at [request]; distinguishes the in-flight cap. */
    suspend fun createJob(request: UploadRequest, resource: Resource): UploadCreateOutcome
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
 * The OS-driven tier's pending→re-invocation rule (capability `background-upload`; drained from
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
 * that **never throws** (capability `background-upload`). The extension root forwards its result
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
): CycleResult =
    // Catches EVERYTHING, cancellation included, and deliberately: this is an ObjC boundary, where a Kotlin
    // throwable — a `CancellationException` no less than any other — aborts the extension process. The one
    // sanctioned catch-all outside the `model/` helpers (named in the catch gate).
    try {
        val ran = try {
            run()
        } catch (t: Throwable) {
            onCycleFailed(t)
            null
        }
        // The hook runs outside the cycle's own guard, so a throwing hook is a LATE failure, not a failed cycle.
        ran?.also(onCycleFinished)?.requeueWhilePending(pending, onRequeue) ?: CycleResult.FAILED
    } catch (t: Throwable) {
        onLateFailure(t)
        CycleResult.FAILED
    }
