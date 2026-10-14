package app.snapsync.mock

import app.snapsync.inbound.Completion
import app.snapsync.inbound.CompositionOnly
import app.snapsync.levers.CreatedJob
import app.snapsync.levers.DownloadSessionLevers
import app.snapsync.levers.DownloadSessionView
import app.snapsync.levers.StartedDownload
import app.snapsync.levers.UploadQueueLevers
import app.snapsync.levers.UploadQueueView
import app.snapsync.levers.UploadSessionLevers
import app.snapsync.levers.UploadSessionView
import app.snapsync.levers.WorldEvent
import app.snapsync.model.BeforeListen
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.HandlerSlot
import app.snapsync.model.destinationPathOf
import app.snapsync.ports.ChangeOutcome
import app.snapsync.ports.CompletedDownload
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import app.snapsync.ports.Files
import app.snapsync.ports.FinishedDownload
import app.snapsync.ports.FinishedTransfer
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.OsUploadJob
import app.snapsync.ports.OsUploadJobHandle
import app.snapsync.ports.OsUploadQueue
import app.snapsync.ports.SessionDrain
import app.snapsync.ports.SessionRelaunch
import app.snapsync.ports.StartResult
import app.snapsync.ports.Transfer
import app.snapsync.ports.TransferFailure
import app.snapsync.ports.TransferHandle
import app.snapsync.ports.TransferHandlers
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.TransferOutcome
import app.snapsync.ports.TransferSession
import app.snapsync.ports.TransferSource
import app.snapsync.ports.UploadJobState
import app.snapsync.ports.UploadTarget
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// The operating system's background transfers (`docs/testing.md`, "Mocks"): what it holds for the app survives the
// app's process — the upload jobs, the download session's transfers — and the operator plays the network by finishing
// them. Each is held to the transfer contract its real adapters satisfy (`OsUploadQueueContract`, `TransferSessionContract`,
// `DownloadContract`).

/**
 * The network an OS transfer crosses: the request's [url] and [headers], answered with an HTTP status — or `null` when
 * no answer came back at all.
 */
fun interface UploadNetwork {
    suspend fun put(url: String, headers: Map<String, String>, bytes: ByteArray): Int?
}

/** Told what a transfer system records, on the app's thread, as the app hands it the work. */
fun interface TransferWatcher {
    fun told(event: WorldEvent.Transfer)
}

/**
 * One job the queue was asked to create: the [job] it names — what its destination names, `<assetId>/<role>` — and the
 * type it declared.
 */
class CreatedUpload(val job: String, val contentType: String)

/**
 * The OS's name for a job made towards [url]: the asset and role its destination names (`<assetId>/<role>`, the byte
 * route's last two segments) — the request is all a job queue keeps of what the job is for.
 */
internal fun jobNameOf(url: String): String = destinationPathOf(url).split('/').takeLast(2).joinToString("/")

/**
 * The PhotoKit tier's upload-job queue: jobs the operating system holds between the app's cycles, presented in its two
 * sets. It decides nothing — which ledger row a job belongs to and what its end means are the upload services'.
 *
 * - `create` enqueues a PENDING job and answers `CREATED`, unless the in-flight cap is reached (`LIMIT_EXCEEDED`), the
 *   create is set to fail, or the resource is not this platform's handle (`FAILED`) — the in-memory library's
 *   [InMemoryResource], as a device's is a PhotoKit resource. [acceptsAnyHandle] is the launch-time adapters' queue on
 *   a device whose photo library is REAL: it takes the resource a real library hands over and moves no bytes of it — a
 *   mocked queue's request carries placeholder bytes to the mocked backend.
 * - A job is named by its destination ([jobNameOf]): the OS keeps no tag.
 * - The operator's `completeJob` performs the job's own request over [network]: a `2xx` makes it terminal-succeeded,
 *   any other status fails it with that status, and no answer fails it with [TransferFailure("network")].
 * - `failJob` fails a job with a chosen error. A first failure is offered for its single free retry; a failure after
 *   it is presented as terminal and handed back for re-creation.
 * - While [restricted] — the device on mobile data, a hotspot or under Low Data Mode — the OS performs NO job, whatever
 *   its request's network rule (measured on the SE2, iOS 26.6.2, 2026-10-03: PhotoKit held a job on a hotspot and in
 *   Low Data Mode with no rule set): `completeJob` leaves it pending.
 */
class UploadQueueMock(
    internal val network: UploadNetwork,
    private val acceptsAnyHandle: Boolean = false,
    internal val restricted: () -> Boolean,
) {
    internal class Job(val contentType: String, val resource: GalleryResourceHandle, var target: UploadTarget) :
        OsUploadJobHandle {
        val name: String get() = jobNameOf(target.url)
        var state: UploadJobState = UploadJobState.PENDING
        var failure: TransferFailure? = null
        var retriedOnce: Boolean = false
    }

    // The app creates and settles jobs from its thread while the operator plays the OS from another: the jobs, and
    // each job's state, are only touched under this lock — never across the network.
    private val lock = mockLock()

    internal fun <T> locked(block: () -> T): T = lock.locked(block)

    internal val jobs = mutableListOf<Job>()
    internal val created = mutableListOf<CreatedUpload>()

    @Volatile internal var jobLimit = Int.MAX_VALUE

    @Volatile internal var failCreate = false

    /** Who is told each job the app creates — the control channel's event stream, or no one. */
    @Volatile private var watcher: TransferWatcher = TransferWatcher {}

    /** Tells [listener] each job the app creates, as it creates it (`docs/testing.md`, "How a test waits"). */
    fun watch(listener: TransferWatcher) {
        watcher = listener
    }

    private val face: OsUploadQueue = Face()

    /** The port face: one for every process, as the OS holds one queue for the app. */
    internal inner class Face : OsUploadQueue {
        override suspend fun retryOffered(): List<OsUploadJob> = locked {
            jobs.filter { it.state == UploadJobState.FAILED && !it.retriedOnce }.map { it.view() }
        }

        override suspend fun terminals(): List<OsUploadJob> = locked {
            jobs.filter {
                it.state == UploadJobState.SUCCEEDED || (it.state == UploadJobState.FAILED && it.retriedOnce)
            }.map { it.view() }
        }

        override suspend fun retry(job: OsUploadJob, target: UploadTarget): ChangeOutcome {
            val j = job.handle as? Job ?: return ChangeOutcome.Refused(null, "not this queue's job")
            locked {
                j.retriedOnce = true
                j.target = target
                j.state = UploadJobState.PENDING
                j.failure = null
            }
            return ChangeOutcome.Applied
        }

        /** Only a job this queue presented is owed an acknowledgement; another is refused, as PhotoKit refuses it. */
        override suspend fun acknowledge(job: OsUploadJob): ChangeOutcome {
            val presented = locked { jobs.remove(job.handle) }
            return if (presented) ChangeOutcome.Applied else ChangeOutcome.Refused(null, "not this queue's job")
        }

        override suspend fun create(resource: GalleryResourceHandle, target: UploadTarget): CreateOutcome {
            if (failCreate) return CreateOutcome.FAILED
            if (resource !is InMemoryResource && !acceptsAnyHandle) return CreateOutcome.FAILED
            val contentType = target.headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
                ?: "application/octet-stream"
            val job = locked {
                if (jobs.size >= jobLimit) return@locked null
                Job(contentType, resource, target).also {
                    jobs.add(it)
                    created.add(CreatedUpload(it.name, contentType))
                }
            } ?: return CreateOutcome.LIMIT_EXCEEDED
            watcher.told(WorldEvent.UploadJobCreated(job.name))
            return CreateOutcome.CREATED
        }
    }

    /** A process's face — the same queue for every process, as the OS holds one for the app. */
    fun port(): OsUploadQueue = face

    val operator: UploadQueueOperator = UploadQueueOperator(this)

    /** The queue as the operating system holds it now — what the operator reads, and the played OS's record. */
    fun view(): UploadQueueView = locked {
        UploadQueueView(
            live = jobs.map { it.name },
            created = created.map { CreatedJob(it.job, it.contentType) },
            jobLimit = jobLimit.takeIf { it != Int.MAX_VALUE },
            failingCreate = failCreate,
        )
    }

    private fun Job.view() = OsUploadJob(
        handle = this,
        destinationUrl = target.url,
        contentType = contentType,
        state = state,
        failure = failure,
        resource = resource.takeIf { state != UploadJobState.SUCCEEDED },
    )
}

/** The operating system's side of the upload-job queue, played: it performs, fails and caps jobs. */
class UploadQueueOperator internal constructor(private val mock: UploadQueueMock) : UploadQueueLevers {
    override suspend fun jobLimit(limit: Int?) {
        mock.jobLimit = limit?.coerceAtLeast(0) ?: Int.MAX_VALUE
    }

    override suspend fun failCreate(on: Boolean) {
        mock.failCreate = on
    }

    override suspend fun queue(): UploadQueueView = mock.view()

    /**
     * The OS performs a pending job's request over the network, and the job settles on the answer — unless the device is
     * on a restricted network, where the OS holds every job (see [UploadQueueMock]) and this leaves it pending. Every
     * live job's, by their names ([jobNameOf]), when [job] is `null`.
     */
    override suspend fun completeJob(job: String?) {
        (job?.let(::listOf) ?: mock.locked { mock.jobs.map { it.name } }).forEach { complete(it) }
    }

    private suspend fun complete(job: String) {
        val (j, target) = mock.locked {
            mock.jobs.firstOrNull { it.name == job && it.state == UploadJobState.PENDING }?.let { it to it.target }
        } ?: return
        if (mock.restricted()) return
        val status = mock.network.put(target.url, target.headers, TRANSFERRED_BYTES)
        mock.locked { settle(j, status) }
    }

    private fun settle(j: UploadQueueMock.Job, status: Int?) {
        when {
            status == null -> {
                j.state = UploadJobState.FAILED
                j.failure = TransferFailure("network")
            }
            status in SUCCESS -> j.state = UploadJobState.SUCCEEDED
            else -> {
                j.state = UploadJobState.FAILED
                j.failure = TransferFailure("http:$status")
            }
        }
    }

    /** A pending job fails with a chosen [error], driving the real retry chain next cycle. */
    override suspend fun failJob(job: String, error: String) = mock.locked {
        val j = mock.jobs.firstOrNull { it.name == job && it.state == UploadJobState.PENDING } ?: return@locked
        j.state = UploadJobState.FAILED
        j.failure = TransferFailure(error)
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
 * transfer's end the moment it happens through its handlers ([TransferHandlers.onFinished]). The transfers are the
 * OS's, so they outlive a process: a relaunched app's handlers hear their ends.
 *
 * The operator plays the OS's network ([UploadSessionOperator.complete]): a transfer lands on [network] as the
 * backend's byte route receives it, and its answer is reported to the process that registered last. A transfer whose
 * request keeps it off the device's current network ([held]) is not performed: it stays live
 * until the network allows it.
 */
class UploadSessionMock(
    internal val network: UploadNetwork,
    /** Whether the device's network holds a transfer created under this rule. */
    internal val held: (TransferNetwork) -> Boolean,
    /** Whether the session also sends a library resource, as Android's in-process session does; a file otherwise. */
    private val sendsResources: Boolean = false,
) {
    /** One live transfer — [from] is the first byte of its file it sends (a resumed upload sends the rest). */
    internal class Live(val tag: String, val target: UploadTarget, val from: Long = 0) : TransferHandle

    // The app creates transfers from its thread while the operator lands them from another; never held across the
    // network or a call into the app's handlers.
    private val lock = mockLock()

    internal fun <T> locked(block: () -> T): T = lock.locked(block)

    internal val handlers = HandlerSlot<TransferHandlers>("TransferSession", BeforeListen.Thrown)

    @Volatile internal var handbacks = 0

    /** The drain reports the session has made — the count a relaunch is told it arrived after. */
    @Volatile internal var drains = 0
    internal val live = mutableListOf<Live>()
    internal val created = mutableListOf<String>()

    /** Who is told each transfer the app creates — the control channel's event stream, or no one. */
    @Volatile private var watcher: TransferWatcher = TransferWatcher {}

    /** Tells [listener] each transfer the app creates, as it creates it (`docs/testing.md`, "How a test waits"). */
    fun watch(listener: TransferWatcher) {
        watcher = listener
    }

    fun port(): TransferSession = Face()

    /** The port face a process is handed. */
    internal inner class Face : TransferSession {
        override val takesResources: Boolean = sendsResources

        @CompositionOnly
        override fun inbound(handlers: TransferHandlers) {
            this@UploadSessionMock.handlers.set(handlers)
        }

        override suspend fun create(source: TransferSource, target: UploadTarget, tag: String): CreateOutcome {
            if (source !is TransferSource.File && !(sendsResources && source is TransferSource.Resource)) {
                return CreateOutcome.FAILED
            }
            val accepted = locked {
                (live.size < LIVE_CAP).also { room ->
                    if (room) {
                        live += Live(tag, target, (source as? TransferSource.File)?.from ?: 0)
                        created += tag
                    }
                }
            }
            if (!accepted) return CreateOutcome.LIMIT_EXCEEDED
            watcher.told(WorldEvent.SessionTransferCreated(tag))
            return CreateOutcome.CREATED
        }

        override suspend fun live(): List<Transfer> =
            locked { live.map { Transfer(it, it.tag) } }

        /** A cancelled transfer ends, and its end is reported like any other — as the platform's delegate does. */
        override suspend fun cancel(transfer: Transfer) {
            val live = transfer.handle as? Live ?: return
            if (locked { this@UploadSessionMock.live.remove(live) }) {
                handlers.orNull("a cancelled transfer's end", BeforeListen.Dropped)
                    ?.onFinished?.deliver(FinishedTransfer(live.tag, TransferFailure("cancelled")))
            }
        }
    }

    val operator: UploadSessionOperator = UploadSessionOperator(this)

    /** The session as the operating system holds it now — what the operator reads, and the played OS's record. */
    fun view(): UploadSessionView = locked { UploadSessionView(live.map { it.tag }, created.toList(), handbacks) }

    /**
     * The operating system relaunches the app for this session's events, handing [completion] — the played OS's
     * delivery (`/os`), never an operator's: a live completion does not cross the channel. The ends of transfers that
     * landed while no process ran were reported as they happened, so the drain report follows at once.
     */
    fun handBack(completion: Completion) {
        val registered = handlers.require("a hand-back")
        registered.onBackgroundEvents.handle(SessionRelaunch(drains), completion)
        handbacks++
        registered.onEventsDrained.deliver(SessionDrain(++drains))
    }

    internal companion object {
        /** The live transfers a session holds before `create` answers `LIMIT_EXCEEDED` — the iOS adapter's cap. */
        const val LIVE_CAP = 4
    }
}

class UploadSessionOperator internal constructor(private val mock: UploadSessionMock) : UploadSessionLevers {
    override suspend fun session(): UploadSessionView = mock.view()

    /**
     * The OS performs the live transfer tagged [key] — every live one when `null` — over the network, and reports its
     * end to the process that registered last — at once, as a running app's session delegate hears it.
     */
    override suspend fun complete(key: String?) {
        (key?.let(::listOf) ?: mock.locked { mock.live.map { it.tag } }).forEach { land(it) }
    }

    private suspend fun land(key: String) {
        val transfer = mock.locked { mock.live.firstOrNull { it.tag == key } } ?: return
        if (mock.held(transfer.target.network)) return
        val bytes = TRANSFERRED_BYTES.copyOfRange(transfer.from.toInt().coerceIn(0, FILE_SIZE), FILE_SIZE)
        val status = mock.network.put(transfer.target.url, transfer.target.headers, bytes)
        mock.locked { mock.live.remove(transfer) }
        val failure = when {
            status == null -> TransferFailure("network")
            status in 200..299 -> null
            else -> TransferFailure("http:$status")
        }
        mock.handlers.require("a transfer's end").onFinished.deliver(FinishedTransfer(transfer.tag, failure))
    }

    private companion object {
        /**
         * Every mocked file's bytes: the mocked photos carry none of their own, and no backend reads these back — a JPEG's
         * markers, then filler, long enough that a transfer from a byte on sends a measurable rest.
         */
        const val FILE_SIZE = 8_192
        val TRANSFERRED_BYTES = ByteArray(FILE_SIZE).also {
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()).copyInto(it)
        }
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
    /** Whether the queue refuses, as it is asked, a URL it cannot fetch — Android's `DownloadManager` takes http(s) only. */
    private val refusesUnfetchable: Boolean = false,
    /** Whether a URL a parser cannot read — an IPv6 host never closed — is refused as it is asked, as iOS's `NSURL` does. */
    private val refusesMalformed: Boolean = false,
    /** Whether the device's network holds a transfer started under this rule. */
    internal val held: (TransferNetwork) -> Boolean,
    /**
     * What the member who uploaded a transfer's bytes would have stored, given what the transfer brought: sealed under
     * an encrypted event's key, as every member's upload is (the encrypted file format, `docs/architecture.md`), or
     * `null` for the bytes unchanged. The device's world answers it ([EventKeyLedger]), never the app.
     */
    private val stored: (description: String, bytes: ByteArray) -> ByteArray?,
) {

    /** A transfer the session holds, until it finishes or is cancelled; [network] is the rule it was started under. */
    class Started(val url: String, val description: String, val network: TransferNetwork = TransferNetwork.ANY) {
        @Volatile var cancelled: Boolean = false

        @Volatile var finished: Boolean = false
    }

    // The app starts and cancels from its thread while the operator finishes from another; never held across a call
    // into the app's handlers.
    private val lock = mockLock()

    internal fun <T> locked(block: () -> T): T = lock.locked(block)

    internal val started = mutableListOf<Started>()
    internal val handlers = HandlerSlot<DownloadHandlers>("Download", BeforeListen.Thrown)

    /** The drain reports the session has made — the count a relaunch is told it arrived after. */
    @Volatile internal var drains = 0

    @Volatile internal var current: Face? = null

    /** Who is told each download the app starts — the control channel's event stream, or no one. */
    @Volatile private var watcher: TransferWatcher = TransferWatcher {}

    /** Tells [listener] each download the app starts, as it starts it (`docs/testing.md`, "How a test waits"). */
    fun watch(listener: TransferWatcher) {
        watcher = listener
    }

    internal inner class Face : Download {
        /** Whether this process has brought the session up — by starting, cancelling, or being handed its events. */
        @Volatile var realized: Boolean = false

        @CompositionOnly
        override fun inbound(handlers: DownloadHandlers) {
            this@DownloadSessionMock.handlers.set(handlers)
            current = this
        }

        override fun start(url: String, headers: Map<String, String>, tag: String, network: TransferNetwork): StartResult {
            realized = true
            if (url.isBlank()) return StartResult.NotStarted
            if (refusesUnfetchable && !url.startsWith("http://") && !url.startsWith("https://")) return StartResult.NotStarted
            if (refusesMalformed && '[' in url && ']' !in url) return StartResult.NotStarted
            locked { started += Started(url, tag, network) }
            watcher.told(WorldEvent.DownloadStarted(tag))
            return StartResult.Started
        }

        /** Cancels every transfer the session holds — a dead process's included — each completing with an error. */
        override suspend fun cancelAll() {
            realized = true
            val cancelled = locked { started.filterNot { it.cancelled }.onEach { it.cancelled = true } }
            cancelled.forEach { registered().onCompleted.deliver(CompletedDownload(it.description, "cancelled")) }
        }
    }

    /** A process's face: a new process has brought the session up not yet. */
    fun port(): Download = Face()

    val operator: DownloadSessionOperator = DownloadSessionOperator(this)

    internal fun leaveTempFile(description: String, bytes: ByteArray? = null): String {
        val brought = bytes ?: TEMP_BYTES
        val left = stored(description, brought) ?: brought
        return temporaryFiles?.leave("download-tmp/${description.hashCode().toUInt()}", left) ?: "temp:/$description"
    }

    internal fun registered(): DownloadHandlers = handlers.require("a download session event")

    /** The session as the operating system holds it now — what the operator reads, and the played OS's record. */
    fun view(): DownloadSessionView = locked {
        DownloadSessionView(
            up = current?.realized == true,
            started = started.map { it.view() },
            inFlight = inFlight().map { it.view() },
        )
    }

    /** The transfers still awaiting a finish, de-duplicated by tag; under the lock. */
    internal fun inFlight(): List<Started> = started.filterNot { it.cancelled || it.finished }.distinctBy { it.description }

    private fun Started.view() = StartedDownload(url, description, cancelled, finished)

    /**
     * The operating system relaunches the app for this session's events, handing [completion] — the played OS's
     * delivery (`/os`), never an operator's: a live completion does not cross the channel.
     */
    fun handBack(completion: Completion) {
        registered().onBackgroundEvents.handle(SessionRelaunch(drains), completion)
        current?.realized = true
    }

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
class DownloadSessionOperator internal constructor(private val mock: DownloadSessionMock) : DownloadSessionLevers {
    override suspend fun downloads(): DownloadSessionView = mock.view()

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun finish(
        description: String?,
        status: Int?,
        expected: Long?,
        received: Long?,
        base64Bytes: String?,
    ) {
        val outcome = if (status == null && expected == null && received == null) {
            DownloadSessionMock.HEALTHY
        } else {
            TransferOutcome(statusCode = status, expectedBytes = expected ?: -1L, receivedBytes = received ?: 0L)
        }
        val bytes = base64Bytes?.let { Base64.decode(it) }
        val descriptions = description?.let(::listOf)
            ?: if (mock.current?.realized == true) mock.locked { mock.inFlight().map { it.description } } else emptyList()
        descriptions.forEach { finish(it, outcome, bytes) }
    }

    /**
     * A finish for [description], as the real delegate delivers one: the facts and a temporary file, then the
     * completion. A rejected [outcome] leaves the resource un-staged. The file holds [bytes] when given and a minimal
     * JPEG otherwise. A transfer the device's network holds ([DownloadSessionMock.held]) does not finish: the OS would
     * not run it.
     */
    internal fun finish(
        description: String,
        outcome: TransferOutcome = DownloadSessionMock.HEALTHY,
        bytes: ByteArray? = null,
    ) {
        val runs = mock.locked {
            val held = mock.started.any { it.description == description && !it.cancelled && !it.finished && mock.held(it.network) }
            if (!held) mock.started.filter { it.description == description }.forEach { it.finished = true }
            !held
        }
        if (!runs) return
        mock.registered().onFinished.deliver(
            FinishedDownload(description, outcome, mock.leaveTempFile(description, bytes)),
        )
        mock.registered().onCompleted.deliver(CompletedDownload(description, null))
    }

    /** The session reports every event delivered (`urlSessionDidFinishEvents`). */
    override suspend fun reportEventsDrained() = drain()

    internal fun drain() {
        mock.registered().onEventsDrained.deliver(SessionDrain(++mock.drains))
    }
}
