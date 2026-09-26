package app.snapsync.ports

import app.snapsync.model.ChangeOutcome
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadSourceKind
import app.snapsync.model.UploadTarget

/**
 * **The platform's background uploads** (capability `background-upload`): on iOS ≥26.1 the PhotoKit upload-job queue
 * the OS runs for the extension, and on every iOS the app's background `URLSession`. One external system, deciding
 * nothing: which ledger row a job belongs to, what a terminal job means, and when a failure is re-created are the upload
 * services' (`:domain:services`, services/upload).
 *
 * The platform's in-flight limit is the adapter's and surfaces as [UploadCreateOutcome.LIMIT_EXCEEDED] — the services
 * create until refused (`URLSession`: four live tasks; PhotoKit: when iOS refuses).
 *
 * An event port. A platform that reports a job's end as it happens (a `URLSession` delegate) tells [UploadHandlers];
 * one that holds terminal jobs until asked (PhotoKit) presents them through [jobs]. The iOS `URLSession` adapter's
 * events are registered by the host zone; the PhotoKit queue raises none.
 */
interface Upload : Listenable<UploadHandlers> {

    /** The kind of source [create] takes: the caller exports the bytes to a file first where this is [UploadSourceKind.FILE]. */
    val accepts: UploadSourceKind

    /** Create a job sending [source] to [target], tagged [tag] where the platform keeps a tag. */
    suspend fun create(source: UploadSource, target: UploadTarget, tag: String): UploadCreateOutcome

    /** The platform's jobs in [set]. A platform without such a set answers none. */
    suspend fun jobs(set: UploadJobSet): List<UploadJob>

    /** Re-point [job], offered for its free retry, at [target]. */
    suspend fun retry(job: UploadJob, target: UploadTarget): ChangeOutcome

    /** Tell the platform [job], presented as terminal, has been dealt with — every presented job is owed this. */
    suspend fun acknowledge(job: UploadJob): ChangeOutcome

    /** Stop [job]. */
    suspend fun cancel(job: UploadJob): ChangeOutcome
}

/** What a platform that reports as it happens tells the core about its uploads. Built only by a composition. */
class UploadHandlers(
    /**
     * [job] reached its terminal state. **Inline**: the fact is persisted before this returns — the platform reports
     * it once, and the process's continued runtime after the callback is not guaranteed.
     */
    val onFinished: (job: UploadJob) -> Unit,
    /**
     * The operating system relaunched (or woke) the app to deliver this session's events, handing [completion] — held
     * across the wake's own work (recording the terminals, which [onFinished] does as they arrive) and released after
     * the drain report.
     */
    val onBackgroundEvents: (completion: Completion) -> Unit,
    /** The session delivered every event it held for the relaunch. */
    val onEventsDrained: () -> Unit,
)
