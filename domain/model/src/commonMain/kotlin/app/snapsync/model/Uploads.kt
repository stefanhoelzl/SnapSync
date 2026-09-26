package app.snapsync.model

/**
 * The vocabulary of the `Upload` port (capability `background-upload`) — what an upload job is, platform-neutrally.
 * Each platform's own job states, error domains and limits stay in its adapter; what they mean for the ledger is the
 * upload services'.
 */

/** What kind of source a platform's uploader takes. */
enum class UploadSourceKind {
    /** The photo library's resource handle itself (the PhotoKit upload-job queue). */
    RESOURCE,

    /** A file the caller exported the bytes to (a background `URLSession`, which uploads from a file). */
    FILE,
}

/** The bytes an upload job sends. */
sealed interface UploadSource {
    /** The photo library's own handle for a resource (opaque to the core). */
    class Resource(val handle: Any) : UploadSource

    /** A platform path of a file holding the bytes — located through `Files`, never stored. */
    data class File(val path: String) : UploadSource
}

/** Where an upload job sends its bytes: the destination URL and the request headers. */
data class UploadTarget(val url: String, val headers: Map<String, String>)

/**
 * Outcome of a create attempt. [CREATED] → the platform job exists (record `UploadStarted`); [LIMIT_EXCEEDED] → the
 * platform's in-flight limit (defer, request re-invocation); [FAILED] → the job could not be created (a malformed
 * destination, an unusable source) and was NOT created, so the caller must NOT record `REQUESTED` for a job that does
 * not exist.
 */
enum class UploadCreateOutcome { CREATED, LIMIT_EXCEEDED, FAILED }

/** Which of the platform's jobs a caller asks for. */
enum class UploadJobSet {
    /** Jobs the platform offers for their one free retry (first failures). */
    RETRY_OFFERED,

    /** Jobs that reached a terminal state and are presented to be acknowledged. */
    TERMINAL,

    /** Jobs still transferring. */
    IN_FLIGHT,
}

/**
 * Where a job stands, as the platform reports it. [UNKNOWN] is a state the platform reported that this build does not
 * know — kept apart from [PENDING] (phase 11f): a guess must read as a guess.
 */
enum class UploadJobState { SUCCEEDED, FAILED, CANCELLED, PENDING, REGISTERED, UNKNOWN }

/**
 * One job as the platform presents it — facts only.
 *
 * [destinationPath] is the path of the URL the job was created with: the one field every platform keeps across a job's
 * whole life, and how the services find its ledger row. [tag] is what the creator tagged it with, where the platform
 * keeps one. [contentType] is the type the job was created with, where the platform stored it. [source] is the job's
 * own source, where the platform still holds it (not for a succeeded job). [handle] is the platform's own job, opaque,
 * for the retry, acknowledgement or cancel the adapter performs on it.
 */
class UploadJob(
    val handle: Any?,
    val tag: String?,
    val destinationPath: String?,
    val contentType: String?,
    val state: UploadJobState,
    val error: UploadError?,
    val source: UploadSource?,
)

/** What the platform answered a change to a job (a retry, an acknowledgement, a cancel). */
sealed interface ChangeOutcome {
    data object Applied : ChangeOutcome

    /** The platform refused it; [code] and [detail] are its own, for the diagnostic line. */
    data class Refused(val code: Long?, val detail: String?) : ChangeOutcome
}
