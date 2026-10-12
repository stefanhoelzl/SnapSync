package app.snapsync.ports

/**
 * The operating system's queue of upload jobs that send a library resource's own bytes — PhotoKit's upload jobs. The
 * OS performs each job, offers a first failure once for its free retry, and presents every job that ended until it is
 * acknowledged. Thin: which ledger row a job belongs to and what its end means are the upload services'.
 */
interface OsUploadQueue : Port {
    /** Make a job sending [resource]'s bytes to [target]. */
    suspend fun create(resource: GalleryResourceHandle, target: UploadTarget): CreateOutcome

    /** The first failures the OS offers for their one free retry. */
    suspend fun retryOffered(): List<OsUploadJob>

    /** The jobs that ended, presented until acknowledged: every one is owed an [acknowledge]. */
    suspend fun terminals(): List<OsUploadJob>

    /** Retry an offered [job] towards [target]. */
    suspend fun retry(job: OsUploadJob, target: UploadTarget): ChangeOutcome

    /** Answer a presented [job]. */
    suspend fun acknowledge(job: OsUploadJob): ChangeOutcome
}

/**
 * One job as the OS presents it — facts only. [destinationUrl] is the URL it was made with, the one fact the OS keeps
 * across its whole life; [contentType] the type it was made with, where the OS stored one; [resource] its own resource,
 * where the OS still holds it (not for a succeeded job). [handle] is the OS's job, for [OsUploadQueue.retry] and
 * [OsUploadQueue.acknowledge].
 */
class OsUploadJob(
    val handle: OsUploadJobHandle,
    val destinationUrl: String?,
    val contentType: String?,
    val state: UploadJobState,
    val failure: TransferFailure?,
    val resource: GalleryResourceHandle?,
)

/** The OS's own job, opaque above the adapter that presented it. */
interface OsUploadJobHandle
