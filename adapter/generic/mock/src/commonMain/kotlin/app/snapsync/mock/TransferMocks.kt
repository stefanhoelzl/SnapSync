package app.snapsync.mock

import app.snapsync.model.AssetId
import app.snapsync.model.BeforeListen
import app.snapsync.model.ChangeOutcome
import app.snapsync.model.HandlerSlot
import app.snapsync.model.StartResult
import app.snapsync.model.TransferOutcome
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadError
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadJobState
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadSourceKind
import app.snapsync.model.TransferNetwork
import app.snapsync.model.UploadTarget
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.destinationPathOf
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.ports.Completion
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import app.snapsync.ports.Files
import app.snapsync.ports.Upload
import app.snapsync.ports.UploadHandlers
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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
 *   device's carry a `PHAssetResource`. [acceptsAnyHandle] is the launch-time adapters' queue on a device whose photo
 *   library is REAL: it takes the `PHAssetResource` a real library hands over and moves no bytes of it — a mocked
 *   queue's request carries placeholder bytes to the mocked backend.
 * - The operator's `completeJob` performs the job's own request over [network]: a `2xx` makes it terminal-succeeded,
 *   any other status fails it with that status, and no answer fails it with [UploadError.Network].
 * - `failJob` fails a job with a chosen error. A first failure is offered for its single free retry; a failure after
 *   it is presented as terminal and handed back for re-creation.
 * - While [restricted] — the device on mobile data, a hotspot or under Low Data Mode — the OS performs NO job, whatever
 *   its request's network rule (measured on the SE2, iOS 26.6.2, 2026-10-03: PhotoKit held a job on a hotspot and in
 *   Low Data Mode with no rule set; capability `mobile-data`): `completeJob` leaves it pending.
 */
class UploadQueueMock(
    internal val network: UploadNetwork,
    private val acceptsAnyHandle: Boolean = false,
    internal val restricted: () -> Boolean = { false },
) {
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
            if (data != Unit && !acceptsAnyHandle) return UploadCreateOutcome.FAILED
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

    /**
     * The OS performs a pending job's request over the network, and the job settles on the answer — unless the device is
     * on a restricted network, where the OS holds every job (see [UploadQueueMock]) and this leaves it pending.
     */
    suspend fun completeJob(key: String) {
        val j = mock.jobs.firstOrNull { it.key == key && it.state == UploadJobState.PENDING } ?: return
        if (mock.restricted()) return
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
 * The app uploader's background transfer session, as the operating system holds it — a `URLSession`'s shape: it sends a
 * FILE, holds at most [LIVE_CAP] live transfers (`LIMIT_EXCEEDED` beyond), offers no free retry, and reports each
 * transfer's end the moment it happens through its handlers ([UploadHandlers.onFinished]) rather than presenting it
 * when asked. The transfers are the OS's, so they outlive a process: a relaunched app's handlers hear their ends.
 *
 * The operator plays the OS's network ([UploadSessionOperator.complete]): a transfer lands on [network] as the
 * backend's byte route receives it, and its answer is reported to the process that registered last. A transfer whose
 * request keeps it off the device's current network ([held], capability `mobile-data`) is not performed: it stays live
 * until the network allows it.
 */
class UploadSessionMock(
    internal val network: UploadNetwork,
    /** Whether the device's network holds a transfer created under this rule (capability `mobile-data`). */
    internal val held: (TransferNetwork) -> Boolean = { false },
) {
    internal class Transfer(val tag: String, val target: UploadTarget)

    internal val handlers = HandlerSlot<UploadHandlers>("Upload", BeforeListen.Thrown)
    internal var handbacks = 0
    internal val live = mutableListOf<Transfer>()
    internal val created = mutableListOf<String>()

    fun port(): Upload = object : Upload {
        override val accepts: UploadSourceKind = UploadSourceKind.FILE

        override fun listen(handlers: UploadHandlers) {
            this@UploadSessionMock.handlers.set(handlers)
        }

        override suspend fun create(source: UploadSource, target: UploadTarget, tag: String): UploadCreateOutcome {
            if (source !is UploadSource.File) return UploadCreateOutcome.FAILED
            if (live.size >= LIVE_CAP) return UploadCreateOutcome.LIMIT_EXCEEDED
            live += Transfer(tag, target)
            created += tag
            return UploadCreateOutcome.CREATED
        }

        /** Only in-flight transfers are known: a terminal one is reported as it ends, and none is offered a retry. */
        override suspend fun jobs(set: UploadJobSet): List<UploadJob> = when (set) {
            UploadJobSet.IN_FLIGHT -> live.map { it.view(UploadJobState.PENDING, null) }
            UploadJobSet.RETRY_OFFERED, UploadJobSet.TERMINAL -> emptyList()
        }

        override suspend fun retry(job: UploadJob, target: UploadTarget): ChangeOutcome =
            ChangeOutcome.Refused(null, "a transfer session has no free retry; a failure is re-created")

        /** Nothing to acknowledge: a transfer's end is reported once, as it happens. */
        override suspend fun acknowledge(job: UploadJob): ChangeOutcome = ChangeOutcome.Applied

        /** A cancelled transfer ends, and its end is reported like any other — as the platform's delegate does. */
        override suspend fun cancel(job: UploadJob): ChangeOutcome {
            val transfer = job.handle as? Transfer ?: return ChangeOutcome.Refused(null, "not this session's transfer")
            if (live.remove(transfer)) {
                handlers.orNull("a cancelled transfer's end", BeforeListen.Dropped)
                    ?.onFinished(transfer.view(UploadJobState.FAILED, UploadError.Cancelled))
            }
            return ChangeOutcome.Applied
        }
    }

    val operator: UploadSessionOperator = UploadSessionOperator(this)

    internal fun Transfer.view(state: UploadJobState, error: UploadError?) = UploadJob(
        handle = this,
        tag = tag,
        destinationPath = destinationPathOf(target.url),
        contentType = null,
        state = state,
        error = error,
        source = null,
    )

    internal companion object {
        /** The live transfers a session holds before `create` answers `LIMIT_EXCEEDED` — the iOS adapter's cap. */
        const val LIVE_CAP = 4
    }
}

class UploadSessionOperator internal constructor(private val mock: UploadSessionMock) {
    /** How many background-event handbacks reached the session. */
    val handbacks: Int get() = mock.handbacks

    /** The tags (upload keys) of the transfers still in flight. */
    fun liveKeys(): List<String> = mock.live.map { it.tag }

    /** Every transfer created, in order, by its tag (a re-created failure shows as a repeated key). */
    val created: List<String> get() = mock.created.toList()

    /**
     * The OS performs the live transfer tagged [key] over the network, and reports its end to the process that
     * registered last — at once, as a running app's session delegate hears it.
     */
    suspend fun complete(key: String) {
        val transfer = mock.live.firstOrNull { it.tag == key } ?: return
        if (mock.held(transfer.target.network)) return
        val status = mock.network.put(transfer.target.url, transfer.target.headers, TRANSFERRED_BYTES)
        mock.live.remove(transfer)
        val (state, error) = when {
            status == null -> UploadJobState.FAILED to UploadError.Network
            status in 200..299 -> UploadJobState.SUCCEEDED to null
            else -> UploadJobState.FAILED to UploadError.Http(status)
        }
        val registered = mock.handlers.require("a transfer's end")
        with(mock) { registered.onFinished(transfer.view(state, error)) }
    }

    /**
     * The operating system relaunches the app for this session's events, handing [completion]. The ends of transfers
     * that landed while no process ran were reported as they happened, so the drain report follows at once.
     */
    fun handBack(completion: Completion) {
        val registered = mock.handlers.require("a hand-back")
        registered.onBackgroundEvents(completion)
        mock.handbacks++
        registered.onEventsDrained()
    }

    private companion object {
        /** A minimal JPEG: the mocked photos carry no bytes, and no backend reads these back. */
        val TRANSFERRED_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
    }
}

/**
 * Where the operating system leaves a finished transfer's bytes: writes [bytes] as a temporary file at [path] in the
 * app's private area and answers the platform path the finish hands the app, which the app adopts from.
 */
fun interface TemporaryFiles {
    fun leave(path: String, bytes: ByteArray): String

    companion object {
        /** Temporary files on [files]' private area — the device's own disk, real or mocked. */
        fun on(files: Files): TemporaryFiles = TemporaryFiles { path, bytes ->
            files.write(FileArea.PRIVATE, path, bytes)
            (files.locate(FileArea.PRIVATE, path) as? FileResult.Ok)?.value ?: "temp:/$path"
        }
    }
}

/**
 * The app's background download session: the transfers the operating system holds for it survive the process, and
 * their completions reach whichever process registered last. A finished transfer's bytes are left as a temporary file
 * through [temporaryFiles], whose platform path the finish hands the app; with none, the path names nothing.
 *
 * The finish mirrors the real `URLSession` delegate, including the ordering the integrity check depends on: the facts
 * and a temporary file, then the completion, whether or not anything went wrong — which is what frees the window slot.
 */
class DownloadSessionMock(
    private val temporaryFiles: TemporaryFiles? = null,
    /** Whether the device's network holds a transfer started under this rule (capability `mobile-data`). */
    internal val held: (TransferNetwork) -> Boolean = { false },
) {

    /** A transfer the session holds, until it finishes or is cancelled; [network] is the rule it was started under. */
    class Started(val url: String, val description: String, val network: TransferNetwork = TransferNetwork.ANY) {
        var cancelled: Boolean = false
        var finished: Boolean = false
    }

    internal val started = mutableListOf<Started>()
    internal val handlers = HandlerSlot<DownloadHandlers>("Download", BeforeListen.Thrown)
    internal var current: Face? = null

    internal inner class Face : Download {
        /** Whether this process has brought the session up — by starting, cancelling, or being handed its events. */
        var realized: Boolean = false

        override fun listen(handlers: DownloadHandlers) {
            this@DownloadSessionMock.handlers.set(handlers)
            current = this
        }

        override fun start(url: String, tag: String, network: TransferNetwork): StartResult {
            realized = true
            if (url.isBlank()) return StartResult.NotStarted
            started += Started(url, tag, network)
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

    internal fun leaveTempFile(description: String, bytes: ByteArray? = null): String =
        temporaryFiles?.leave("download-tmp/${description.hashCode().toUInt()}", bytes ?: TEMP_BYTES) ?: "temp:/$description"

    internal fun registered(): DownloadHandlers = handlers.require("a download session event")

    companion object {
        /** An ordinary healthy transfer: `200`, no declared length. */
        val HEALTHY: TransferOutcome = TransferOutcome(statusCode = 200, expectedBytes = -1L, receivedBytes = 1_024L)

        /**
         * What a mocked download's temporary file holds: a 16×16 JPEG — real image bytes, so a launch whose adapters
         * keep the REAL photo library imports a mocked event photo as it imports any other (`docs/testing.md`, "Launch-time
         * adapters"). The in-memory library reads only their presence.
         */
        @OptIn(ExperimentalEncodingApi::class)
        private val TEMP_BYTES = Base64.decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDABALDA4MChAODQ4SERATGCgaGBYWGDEjJR0oOjM9PDkzODdASFxOQERXRTc4UG1RV19iZ2hn" +
                "Pk1xeXBkeFxlZ2P/2wBDARESEhgVGC8aGi9jQjhCY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2Nj" +
                "Y2NjY2P/wAARCAAQABADASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAP/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFAEB" +
                "AAAAAAAAAAAAAAAAAAAABf/EABQRAQAAAAAAAAAAAAAAAAAAAAD/2gAMAwEAAhEDEQA/AKABiz//2Q==",
        )
    }
}

/** The operating system's side of the download session, played: it finishes transfers and hands events back. */
class DownloadSessionOperator internal constructor(private val mock: DownloadSessionMock) {
    /** Every transfer ever started, cancelled ones included. */
    val started: List<DownloadSessionMock.Started> get() = mock.started.toList()

    /** Whether the running process has brought the session up. */
    val realized: Boolean get() = mock.current?.realized == true

    /** The transfers still awaiting a finish, de-duplicated by tag. */
    fun inFlight(): List<DownloadSessionMock.Started> =
        mock.started.filterNot { it.cancelled || it.finished }.distinctBy { it.description }

    /**
     * A finish for [description], as the real delegate delivers one: the facts and a temporary file, then the
     * completion. A rejected [outcome] leaves the resource un-staged. The file holds [bytes] when given — the
     * operator choosing what the transfer brought, e.g. a real motion photo — and a minimal JPEG otherwise. A transfer
     * the device's network holds ([DownloadSessionMock.held]) does not finish: the OS would not run it.
     */
    fun finish(description: String, outcome: TransferOutcome = DownloadSessionMock.HEALTHY, bytes: ByteArray? = null) {
        if (mock.started.any { it.description == description && !it.cancelled && !it.finished && mock.held(it.network) }) return
        mock.started.filter { it.description == description }.forEach { it.finished = true }
        mock.registered().onFinished(description, outcome, mock.leaveTempFile(description, bytes))
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
