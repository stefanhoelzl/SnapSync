package app.snapsync.levers

import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.descriptor.serviceDescriptorOf
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.serialization.Serializable

// The operating system's background transfers, as an operator plays them (`docs/testing.md`, "Typed services"): the
// upload-job queue the OS-driven extension creates jobs on, the app uploader's transfer session, and the download
// session. Nothing lands until the operator lands it, so each is a hold a test releases.

/**
 * **The upload-job queue's operator face** — the operating system's PhotoKit queue, played: it caps, fails and performs
 * the jobs the extension creates. A job is named by what its destination names, `<assetId>/<role>`.
 */
@Rpc
interface UploadQueueLevers {
    /** The in-flight cap: `create` answers `LIMIT_EXCEEDED` at or above [limit]; omitted, there is none. */
    suspend fun jobLimit(limit: Int? = null)

    /** Whether `create` answers `FAILED`, as for an unusable payload. */
    suspend fun failCreate(on: Boolean)

    /**
     * The OS performs [job]'s request over the network — omitted, every live job's — and each settles on the answer:
     * a `2xx` succeeds it, any other status or no answer fails it. On a restricted network the OS performs none.
     */
    suspend fun completeJob(job: String? = null)

    /** A pending [job] fails with [error] — what the OS says of it (`network`, `http:503`); nothing reads it but logs. */
    suspend fun failJob(job: String, error: String)

    /** The queue as the operating system holds it now. */
    suspend fun queue(): UploadQueueView

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<UploadQueueLevers>(
        UploadQueueLevers::class,
        "upload-queue",
        setOf(LeverNeed.UPLOAD_QUEUE_MOCKED),
        { serviceDescriptorOf() },
    )
}

/** The upload-job queue: its [live] jobs (in flight, or ended and not yet acknowledged), and every one [created]. */
@Serializable
data class UploadQueueView(
    val live: List<String>,
    /** In order; a retried chain shows as a repeated name. */
    val created: List<CreatedJob>,
    /** The in-flight cap, or `null` for none. */
    val jobLimit: Int?,
    val failingCreate: Boolean,
)

/** A job the queue created: the [job] its destination names and the type it declared. */
@Serializable
data class CreatedJob(val job: String, val contentType: String)

/**
 * **The app uploader's transfer session's operator face** — the operating system's background `URLSession`, played: it
 * lands the transfers the app hands it, each end reported to the app as it happens. A transfer is named by its tag, the
 * upload key.
 */
@Rpc
interface UploadSessionLevers {
    /**
     * The OS performs the live transfer tagged [key] — omitted, every live one — and reports each end to the app. A
     * transfer the device's network holds is not performed.
     */
    suspend fun complete(key: String? = null)

    /** The session as the operating system holds it now. */
    suspend fun session(): UploadSessionView

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<UploadSessionLevers>(
        UploadSessionLevers::class,
        "upload-session",
        setOf(LeverNeed.UPLOAD_SESSION_MOCKED),
        { serviceDescriptorOf() },
    )
}

/** The app uploader's session: the keys still [live], every transfer [created], and the relaunches it handed back. */
@Serializable
data class UploadSessionView(
    val live: List<String>,
    /** In order; a re-created failure shows as a repeated key. */
    val created: List<String>,
    val handbacks: Int,
)

/**
 * **The download session's operator face** — the operating system's background download session, played: it finishes
 * the transfers the app started and reports its events drained. A transfer is named by its description, the tag the
 * app started it under.
 */
@Rpc
interface DownloadSessionLevers {
    /**
     * The OS finishes [description]'s transfer — omitted, every in-flight one, and none while this launch has not
     * brought its session up — as the real delegate does: the facts and a temporary file, then the completion.
     *
     * With no [status], [expected] or [received] it is a healthy `200`; otherwise it answered [status] (`null`: none),
     * declaring [expected] bytes (`null`: no length) and bringing [received] (`null`: none) — a rejected answer leaves
     * the resource un-staged. The file holds [base64Bytes], decoded, when given — the operator choosing what the
     * transfer brought, a real motion photo, say — and a minimal JPEG otherwise. A transfer the device's network holds
     * does not finish.
     */
    suspend fun finish(
        description: String? = null,
        status: Int? = null,
        expected: Long? = null,
        received: Long? = null,
        base64Bytes: String? = null,
    )

    /** The session reports every event delivered (`urlSessionDidFinishEvents`). */
    suspend fun reportEventsDrained()

    /** The session as the operating system holds it now. */
    suspend fun downloads(): DownloadSessionView

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<DownloadSessionLevers>(
        DownloadSessionLevers::class,
        "download-session",
        setOf(LeverNeed.DOWNLOADS_MOCKED),
        { serviceDescriptorOf() },
    )
}

/** The download session: whether this launch brought it [up], every transfer [started], and those still [inFlight]. */
@Serializable
data class DownloadSessionView(
    val up: Boolean,
    /** Cancelled and finished ones included. */
    val started: List<StartedDownload>,
    /** Awaiting a finish, one per description. */
    val inFlight: List<StartedDownload>,
)

/** A transfer the app started: from [url], under its [description]. */
@Serializable
data class StartedDownload(val url: String, val description: String, val cancelled: Boolean, val finished: Boolean)
