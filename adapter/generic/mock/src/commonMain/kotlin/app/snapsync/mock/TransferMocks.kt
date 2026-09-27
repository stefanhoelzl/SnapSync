package app.snapsync.mock

import app.snapsync.model.AssetId
import app.snapsync.model.ChangeOutcome
import app.snapsync.model.StartResult
import app.snapsync.model.TransferOutcome
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadError
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadJobState
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadSourceKind
import app.snapsync.model.UploadTarget
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.destinationPathOf
import app.snapsync.ports.Completion
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import app.snapsync.ports.Upload
import app.snapsync.ports.UploadHandlers

// The operating system's background transfers (`docs/testing.md`, "Mocks"): what it holds for the app survives the
// app's process — the upload jobs, the download session's transfers — and the operator plays the network by finishing
// them. Each is held to the transfer contract its real adapters satisfy (`UploadContract`, `DownloadContract`).

/**
 * The network an OS transfer crosses: the request's [url] and [headers], answered with an HTTP status — or `null` when
 * no answer came back at all.
 */
fun interface UploadNetwork {
    suspend fun put(url: String, headers: Map<String, String>, bytes: ByteArray): Int?
}

/** One job the queue was asked to create: the upload key it was tagged with, and the type it declared. */
class CreatedUpload(val filename: String, val contentType: String) {
    /** The photo's id, recovered from the key (`<assetId>-<role>.<ext>`). */
    val assetId: AssetId get() = assetIdFromUploadKey(filename)
}

/**
 * The PhotoKit tier's upload-job queue: jobs the operating system holds between the app's cycles, presented in its two
 * sets. It decides nothing — which ledger row a job belongs to and what its end means are the upload services'.
 *
 * - `create` enqueues a PENDING job and answers `CREATED`, unless the in-flight cap is reached (`LIMIT_EXCEEDED`), the
 *   create is set to fail, or the source is not this platform's handle (`FAILED`) — its photos carry `Unit`, as a
 *   device's carry a `PHAssetResource`.
 * - The operator's `completeJob` performs the job's own request over [network]: a `2xx` makes it terminal-succeeded,
 *   any other status fails it with that status, and no answer fails it with [UploadError.Network].
 * - `failJob` fails a job with a chosen error. A first failure is offered for its single free retry; a failure after
 *   it is presented as terminal and handed back for re-creation.
 */
class UploadQueueMock(internal val network: UploadNetwork) {
    internal class Job(val key: String, val contentType: String, val data: Any, var target: UploadTarget) {
        var state: UploadJobState = UploadJobState.PENDING
        var error: UploadError? = null
        var retriedOnce: Boolean = false
    }

    internal val jobs = mutableListOf<Job>()
    internal val created = mutableListOf<CreatedUpload>()
    internal var jobLimit = Int.MAX_VALUE
    internal var failCreate = false

    private val face: Upload = object : Upload {
        override val accepts: UploadSourceKind = UploadSourceKind.RESOURCE

        /** The queue raises no events: its terminal jobs are presented when asked, as PhotoKit's are. */
        override fun listen(handlers: UploadHandlers) = Unit

        override suspend fun jobs(set: UploadJobSet): List<UploadJob> = when (set) {
            UploadJobSet.RETRY_OFFERED -> jobs.filter { it.state == UploadJobState.FAILED && !it.retriedOnce }
            UploadJobSet.TERMINAL -> jobs.filter {
                it.state == UploadJobState.SUCCEEDED || (it.state == UploadJobState.FAILED && it.retriedOnce)
            }
            UploadJobSet.IN_FLIGHT -> jobs.filter { it.state == UploadJobState.PENDING }
        }.map { it.view() }

        override suspend fun retry(job: UploadJob, target: UploadTarget): ChangeOutcome {
            val j = job.handle as? Job ?: return ChangeOutcome.Refused(null, "not this queue's job")
            j.retriedOnce = true
            j.target = target
            j.state = UploadJobState.PENDING
            j.error = null
            return ChangeOutcome.Applied
        }

        override suspend fun acknowledge(job: UploadJob): ChangeOutcome {
            jobs.remove(job.handle)
            return ChangeOutcome.Applied
        }

        override suspend fun cancel(job: UploadJob): ChangeOutcome {
            jobs.remove(job.handle)
            return ChangeOutcome.Applied
        }

        override suspend fun create(source: UploadSource, target: UploadTarget, tag: String): UploadCreateOutcome {
            if (failCreate) return UploadCreateOutcome.FAILED
            val data = (source as? UploadSource.Resource)?.handle ?: return UploadCreateOutcome.FAILED
            if (data != Unit) return UploadCreateOutcome.FAILED
            if (jobs.size >= jobLimit) return UploadCreateOutcome.LIMIT_EXCEEDED
            val contentType = target.headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
                ?: "application/octet-stream"
            jobs.add(Job(tag, contentType, data, target))
            created.add(CreatedUpload(tag, contentType))
            return UploadCreateOutcome.CREATED
        }
    }

    /** A process's face — the same queue for every process, as the OS holds one for the app. */
    fun port(): Upload = face

    val operator: UploadQueueOperator = UploadQueueOperator(this)

    private fun Job.view() = UploadJob(
        handle = this,
        tag = key,
        destinationPath = destinationPathOf(target.url),
        contentType = contentType,
        state = state,
        error = error,
        source = UploadSource.Resource(data).takeIf { state != UploadJobState.SUCCEEDED },
    )
}

/** The operating system's side of the upload-job queue, played: it performs, fails and caps jobs. */
class UploadQueueOperator internal constructor(private val mock: UploadQueueMock) {
    /** The in-flight cap: `create` answers `LIMIT_EXCEEDED` at or above it. */
    var jobLimit: Int
        get() = mock.jobLimit
        set(value) { mock.jobLimit = value }

    /** `create` answers `FAILED` — an unusable payload. */
    var failCreate: Boolean
        get() = mock.failCreate
        set(value) { mock.failCreate = value }

    /** Every job created, in order (retry chains show as repeated keys). */
    val created: List<CreatedUpload> get() = mock.created.toList()

    /** The keys of every live (in-flight or terminal-unacknowledged) job. */
    fun liveJobKeys(): List<String> = mock.jobs.map { it.key }

    /** The OS performs a pending job's request over the network, and the job settles on the answer. */
    suspend fun completeJob(key: String) {
        val j = mock.jobs.firstOrNull { it.key == key && it.state == UploadJobState.PENDING } ?: return
        val status = mock.network.put(j.target.url, j.target.headers, TRANSFERRED_BYTES)
        when {
            status == null -> {
                j.state = UploadJobState.FAILED
                j.error = UploadError.Network
            }
            status in SUCCESS -> j.state = UploadJobState.SUCCEEDED
            else -> {
                j.state = UploadJobState.FAILED
                j.error = UploadError.Http(status)
            }
        }
    }

    /** A pending job fails with a chosen [error], driving the real retry chain next cycle. */
    fun failJob(key: String, error: UploadError) {
        val j = mock.jobs.firstOrNull { it.key == key && it.state == UploadJobState.PENDING } ?: return
        j.state = UploadJobState.FAILED
        j.error = error
    }

    private companion object {
        val SUCCESS = 200..299

        /** A minimal JPEG: the mocked photos carry no bytes, and no backend reads these back. */
        val TRANSFERRED_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
    }
}

/**
 * The app uploader's background transfer session, as the operating system holds it: no jobs — the JVM compositions'
 * app uploader creates none — and its handlers are those of the process that registered last.
 */
class UploadSessionMock {
    internal var handlers: UploadHandlers? = null
    internal var handbacks = 0

    fun port(): Upload = object : Upload {
        override val accepts: UploadSourceKind = UploadSourceKind.FILE

        override fun listen(handlers: UploadHandlers) {
            this@UploadSessionMock.handlers = handlers
        }

        override suspend fun create(source: UploadSource, target: UploadTarget, tag: String): UploadCreateOutcome =
            UploadCreateOutcome.FAILED

        override suspend fun jobs(set: UploadJobSet): List<UploadJob> = emptyList()

        override suspend fun retry(job: UploadJob, target: UploadTarget): ChangeOutcome = ChangeOutcome.Applied

        override suspend fun acknowledge(job: UploadJob): ChangeOutcome = ChangeOutcome.Applied

        override suspend fun cancel(job: UploadJob): ChangeOutcome = ChangeOutcome.Applied
    }

    val operator: UploadSessionOperator = UploadSessionOperator(this)
}

class UploadSessionOperator internal constructor(private val mock: UploadSessionMock) {
    /** How many background-event handbacks reached the session. */
    val handbacks: Int get() = mock.handbacks

    /**
     * The operating system relaunches the app for this session's events, handing [completion]. Nothing is in flight, so
     * the session has nothing to deliver: its drain report follows at once.
     */
    fun handBack(completion: Completion) {
        val registered = checkNotNull(mock.handlers) { "no process listened to the upload session" }
        registered.onBackgroundEvents(completion)
        mock.handbacks++
        registered.onEventsDrained()
    }
}

/**
 * The app's background download session: the transfers the operating system holds for it survive the process, and
 * their completions reach whichever process registered last. [leaveTempFile] is where the OS leaves a finished
 * transfer's bytes: given its description, it writes the temporary file and answers the platform path the finish hands
 * the app.
 *
 * The finish mirrors the real `URLSession` delegate, including the ordering the integrity check depends on: the facts
 * and a temporary file, then the completion, whether or not anything went wrong — which is what frees the window slot.
 */
class DownloadSessionMock(internal val leaveTempFile: (description: String) -> String = { "temp:/$it" }) {

    /** A transfer the session holds. */
    class Started(val url: String, val description: String) {
        var cancelled: Boolean = false
    }

    internal val started = mutableListOf<Started>()
    internal var handlers: DownloadHandlers? = null
    internal var current: Face? = null

    internal inner class Face : Download {
        /** Whether this process has brought the session up — by starting, cancelling, or being handed its events. */
        var realized: Boolean = false

        override fun listen(handlers: DownloadHandlers) {
            this@DownloadSessionMock.handlers = handlers
            current = this
        }

        override fun start(url: String, tag: String): StartResult {
            realized = true
            if (url.isBlank()) return StartResult.NotStarted
            started += Started(url, tag)
            return StartResult.Started
        }

        /** Cancels every transfer the session holds — a dead process's included — each completing with an error. */
        override suspend fun cancelAll() {
            realized = true
            started.filterNot { it.cancelled }.forEach {
                it.cancelled = true
                registered().onCompleted(it.description, "cancelled")
            }
        }
    }

    /** A process's face: a new process has brought the session up not yet. */
    fun port(): Download = Face()

    val operator: DownloadSessionOperator = DownloadSessionOperator(this)

    internal fun registered(): DownloadHandlers =
        checkNotNull(handlers) { "no process listened to the download session — nothing would receive this" }

    companion object {
        /** An ordinary healthy transfer: `200`, no declared length. */
        val HEALTHY: TransferOutcome = TransferOutcome(statusCode = 200, expectedBytes = -1L, receivedBytes = 1_024L)
    }
}

/** The operating system's side of the download session, played: it finishes transfers and hands events back. */
class DownloadSessionOperator internal constructor(private val mock: DownloadSessionMock) {
    /** Every transfer ever started, cancelled ones included. */
    val started: List<DownloadSessionMock.Started> get() = mock.started.toList()

    /** Whether the running process has brought the session up. */
    val realized: Boolean get() = mock.current?.realized == true

    /** The transfers still awaiting a finish, de-duplicated by tag. */
    fun inFlight(): List<DownloadSessionMock.Started> = mock.started.filterNot { it.cancelled }.distinctBy { it.description }

    /**
     * A finish for [description], as the real delegate delivers one: the facts and a temporary file, then the
     * completion. A rejected [outcome] leaves the resource un-staged.
     */
    fun finish(description: String, outcome: TransferOutcome = DownloadSessionMock.HEALTHY) {
        mock.registered().onFinished(description, outcome, mock.leaveTempFile(description))
        mock.registered().onCompleted(description, null)
    }

    /** The operating system relaunches the app for this session's events, handing [completion]. */
    fun handBack(completion: Completion) {
        mock.registered().onBackgroundEvents(completion)
        mock.current?.realized = true
    }

    /** The session reports every event delivered (`urlSessionDidFinishEvents`). */
    fun reportEventsDrained() = mock.registered().onEventsDrained()
}
