package app.snapsync.services.upload

import app.snapsync.model.Resource
import app.snapsync.model.UploadError
import app.snapsync.model.UploadRequest
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.PlatformUploadJob

/**
 * The transfer lifecycle of one background-upload cycle — creating, retrying and settling upload jobs. The
 * production implementation is [UploadTransferService], over the thin `Upload` port that both tiers' platform
 * adapters implement; the cycle holds this seam rather than that service so its own orchestration stays testable
 * against a scripted transfer (`UploadCycle`'s tests). What the cycle reads
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
