package app.snapsync.ports

import app.snapsync.model.Resource
import app.snapsync.model.UploadError
import app.snapsync.model.UploadRequest
import app.snapsync.model.UploadCreateOutcome

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
