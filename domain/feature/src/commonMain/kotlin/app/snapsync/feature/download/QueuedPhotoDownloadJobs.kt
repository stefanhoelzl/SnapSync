package app.snapsync.feature.download

import app.snapsync.model.ConfinedTo
import app.snapsync.ports.OsCompletions
import app.snapsync.ports.DownloadTask
import app.snapsync.ports.DownloadTransport
import app.snapsync.ports.DownloadTransportHost
import app.snapsync.ports.PhotoDownloadJobs
import app.snapsync.ports.StagedBytes
import app.snapsync.model.TransferOutcome

import app.snapsync.model.AssetRef
import app.snapsync.ports.EntryContext
import app.snapsync.model.runCatchingCancellable
import app.snapsync.model.PendingDownload
import app.snapsync.ports.invocation
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Bounded in-flight window (Apple: keep background tasks in the low hundreds; we stay well under). */
internal const val MAX_IN_FLIGHT = 24

/** taskDescription field separator — a newline cannot occur in device ids / sanitized keys / filenames. */
private const val SEP = "\n"

/** What a transfer's opaque description decodes back to. */
internal data class TaskTag(val ref: AssetRef, val resourceKey: String)

internal fun encodeTag(ref: AssetRef, resourceKey: String): String =
    listOf(ref.sourceDeviceId, ref.sourceAssetId, resourceKey).joinToString(SEP)

internal fun decodeTag(description: String): TaskTag? {
    val parts = description.split(SEP)
    if (parts.size != 3) return null
    return TaskTag(AssetRef(parts[0], parts[1]), parts[2])
}

/** Where a resource's bytes land in durable staging, relative to the shared area. `/` is not legal in a path segment. */
internal fun stagingPath(root: String, ref: AssetRef, resourceKey: String): String =
    "$root/${ref.sourceDeviceId.replace('/', '_')}/${resourceKey.replace('/', '_')}"

/**
 * Whether a finished transfer's bytes may be staged (capability `receiving-photos`).
 *
 * A background `URLSession` reports an HTTP error as a *successful transfer of an error body*: the
 * finish callback fires with the `502` document in hand and the completion error is `nil`. So this is the
 * only thing that sees a non-2xx, and without it the error body is staged, fails to import, and is retried
 * forever — the transfer is never re-run once its resource is recorded as staged.
 *
 * Rejects only on **positive evidence**: a known non-2xx status, or a known length the body falls short of.
 * Never on absence of evidence. That asymmetry is not caution — a rejected transfer is retried, and a retry
 * only helps if the condition can change. A server that omits `Content-Length` omits it every time, so
 * rejecting an unknown length would loop unboundedly and the photo would never arrive: the same permanent,
 * invisible loss as accepting bad bytes, reached from the other side. Hence unknown length, over-length and
 * unknown status all pass.
 */
internal fun TransferOutcome.mayBeStaged(): Boolean {
    if (statusCode != null && statusCode !in 200..299) return false
    if (expectedBytes >= 0 && receivedBytes < expectedBytes) return false
    return true
}

/**
 * A presigned S3 link the background session can actually fetch. `NSURL` happily parses a hostless or
 * non-HTTP string, and handing one to a background session raises an uncatchable Objective-C exception —
 * so the guard lives here, where it is testable, not at the edge.
 */
internal fun isFetchableUrl(url: String): Boolean {
    val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") return false
    val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    return authority.substringAfterLast('@').substringBefore(':').isNotEmpty()
}

/**
 * The iOS [PhotoDownloadJobs] (capability `receiving-photos`), platform-free: a pending queue drained
 * through a bounded in-flight window into a [DownloadTransport] (on iOS a background `URLSession`, in
 * tests a fake). Each finished transfer is staged durably and reported via `onStaged`; the window refills
 * as transfers complete.
 *
 * **Cancellation cancels tasks, never the transport.** [cancelAll] drops the queue and cancels each
 * outstanding [DownloadTask]; the transport survives, so the next reconcile after a leave/switch enqueues
 * normally. Destroying the transport instead is what aborted the app in production — see
 * [DownloadTransport]. A transport the *system* invalidates is discarded and rebuilt on the next transfer.
 */
class QueuedPhotoDownloadJobs(
    private val scope: CoroutineScope,
    /**
     * Where staged bytes live: a relative root and the one lookup that turns a relative path into the platform
     * path the transport writes to. The staged path this class reports is the RELATIVE one, so the download store
     * never holds an absolute container path (a restored device's container may move).
     */
    private val staging: StagedBytes,
    private val newTransport: (DownloadTransportHost) -> DownloadTransport,
    /**
     * Deliver a staged resource. Required, and bound at construction (law "Callbacks are bound at
     * construction", `docs/architecture.md`): this used to be a nullable `var` the composition
     * assigned while building the download controller, so a process the OS relaunched only to deliver
     * download-session events — which builds the jobs and nothing else — dropped every staged resource
     * without a line in the log. The composition's binding resolves the controller when it is invoked.
     *
     * `suspend`, and launched HERE rather than by the composition, so this class can track the staging
     * it starts: the wake's OS handler is released only once every staging it announced is recorded. The
     * import that follows is not this callback's — it is the process tail's first unit.
     */
    private val onStaged: suspend (AssetRef, resourceKey: String, stagedPath: String) -> Unit,
    // Where this session's OS completion handler is released (capability `sync-status`). UIKit owns
    // that handler and requires the main thread; the drain that triggers the release arrives on a
    // session-owned queue, so the lane is the only thing putting it where it belongs. The default is
    // reached ONLY by direct construction in unit tests: both compositions pass one — the app its main
    // lane, the world harness its own composition lane.
    private val uiLane: CoroutineContext = EmptyCoroutineContext,
    private val log: Logger = Logger.withTag("PhotoDownloadJobs"),
    // The ambient entry-point prefix, so every line a background-events wake causes traces back to it.
    private val entryContext: EntryContext = EntryContext.NoOp,
) : PhotoDownloadJobs {

    /**
     * The OS completion handlers of this session's background-events wakes (capability `sync-status`), held across
     * the wake's own work — **staging** the delivered files — and released at the session's drain report once that
     * staging is recorded; never held for the photo-library imports, which are the process tail's first unit
     * (capability `receiving-photos`, "The download session's OS handler is released after staging"; decision record
     * `changes/own-work-per-wake`, D5). No deadline of the app's own bounds the hold: a drain report that never comes
     * ends in the operating system's expiry, which the wake's owner forwards to the [OsCompletions.Handover] it was
     * handed by [adoptBackgroundEvents].
     */
    private val backgroundEvents = OsCompletions(
        entryPoint = "download.onBackgroundSessionEvents",
        releaseLane = uiLane,
        log = log,
    )

    /**
     * Serialises the drains: taking the outstanding stagings is a **destructive** read, so a second drain overlapping
     * the first would find the list empty and release its handlers against stagings that are still being recorded.
     */
    private val drains = Mutex()

    /**
     * The stagings started by [DownloadTransportHost.onStaged] since the last drain. Held so the OS's background-events
     * handler is released *after* they are recorded (capability `receiving-photos`) — the session reports its own
     * events drained, which says nothing about the store writes they caused.
     *
     * A thread-safe cell, not a plain list (law "State reached from OS callbacks is confined", capability
     * `docs/architecture.md`): [DownloadTransportHost.onStaged] registers from the transport's delegate queue while
     * [awaitOutstandingStagings] takes the list from a coroutine, and a plain list shared between them could drop a
     * registration — releasing the OS handler before a staging it announced — or throw mid-iteration.
     */
    private val outstandingStagings = MutableStateFlow<List<Job>>(emptyList())

    /**
     * The not-yet-started transfers, keyed by transfer description like [inFlight] — so a key is queued at
     * most once, and a key is never both queued and in flight ([PhotoDownloadJobs.enqueue] is idempotent).
     *
     * This was an `ArrayDeque`, and [enqueue] appended every download it was handed. Every reconcile hands
     * over its WHOLE pending snapshot, so a second reconcile while a backlog was still queued doubled it: the
     * second copy of a queued key started after the first had finished, re-downloading a resource whose asset
     * was already imported, and a key running at the time went back in the queue behind it. Measured on the
     * SE2 (S2 bench, 2026-09-24): 136 transfers for 100 resources — and the stale copies then held the window,
     * so the next reconcile's new photos sat in this in-memory queue with no transfer behind them, and a
     * SIGKILL lost them while the OS finished only the stale copies (a cold relaunch staged 24, imported 0).
     */
    @ConfinedTo("composition")
    private val queued = LinkedHashMap<String, PendingDownload>()

    /** The bounded window, keyed by transfer description — which also makes a re-enqueue idempotent. */
    @ConfinedTo("composition")
    private val inFlight = LinkedHashMap<String, DownloadTask>()

    @ConfinedTo("composition")
    private var transport: DownloadTransport? = null

    private val host = object : DownloadTransportHost {
        override fun accepts(description: String, outcome: TransferOutcome): Boolean {
            val staged = outcome.mayBeStaged()
            // Logged for EVERY finished transfer, not only rejections (capability `privacy-security`).
            // The accept path is the one that matters most in the field: `expectedBytes = -1` here means the
            // server sent no `Content-Length`, so the length check is inert and this transfer is admitted on
            // status alone. If that is what bunny's S3 GETs actually look like, then admitting an unknown
            // length is the live path for every photo rather than an edge case — and a stricter rule would
            // have rejected them all. That is not knowable from a unit test; it is knowable from this line.
            log.i {
                "transfer finished: status=${outcome.statusCode} expected=${outcome.expectedBytes} " +
                    "received=${outcome.receivedBytes} → ${if (staged) "stage" else "REJECT (will re-download)"}"
            }
            // A rejection is not an error path for the window: the transport still reports `onCompleted`
            // for this task (a download's completion callback fires after its finish callback, error or
            // not), which frees the slot. Leaving the bytes un-staged is the whole point — the resource
            // stays un-staged, so the next reconcile re-downloads it instead of re-importing garbage.
            return staged
        }

        /**
         * Derived purely from the description, so a completion delivered after a background **relaunch** —
         * for a transfer this process never started, and which is therefore in no in-memory map — still
         * knows where its bytes belong.
         */
        override fun destinationFor(description: String): String? {
            val tag = decodeTag(description) ?: return null
            // An unreachable shared area names no destination: the bytes are not staged, and the resource is
            // downloaded again by a later reconcile — never staged somewhere the release side cannot find.
            return runCatchingCancellable { staging.locate(relativePath(tag)) }
                .onFailure { log.w(it) { "no staging destination for $description — not staged" } }
                .getOrNull()
        }

        override fun onStaged(description: String, stagedPath: String) {
            val tag = decodeTag(description) ?: run {
                log.w { "staged bytes carry an undecodable transfer description — not imported: $description" }
                return
            }
            // What is recorded is the RELATIVE path the destination was located from — the same function of the
            // description — never the platform path the transport reports.
            val relative = relativePath(tag)
            // Launched here, and REMEMBERED: this fires on the transport's delegate queue, which must
            // not be blocked by a store write, but the job has to remain reachable so the wake's OS handler
            // can wait for it. Pruning completed jobs keeps the list from growing across a long session.
            val recording = scope.launch { onStaged(tag.ref, tag.resourceKey, relative) }
            outstandingStagings.update { held -> held.filterNot { it.isCompleted } + recording }
        }

        override fun onCompleted(description: String, error: String?) {
            if (error != null) log.w { "download task failed (will retry): $error" }
            scope.launch {
                inFlight.remove(description)
                pump()
            }
        }

        override fun onInvalidated() {
            // The SYSTEM invalidated the session (we never do). Drop it so the next transfer rebuilds one;
            // reusing it would create a task on a dead session and abort the process.
            log.w { "download transport was invalidated by the system — rebuilding on next transfer" }
            scope.launch {
                transport = null
                inFlight.clear()
                pump()
            }
        }

        /**
         * The session has delivered every event it had. That is not yet the wake's own work done: each delivery
         * started a staging, and recording those is what the OS handler reports on (capability `receiving-photos`).
         * So join them first, then release every handler outstanding — never waiting for the imports, which are
         * the tail's.
         *
         * Unconditional: a foreground drain has no handler waiting on it, but the stagings it announces are joined
         * all the same, and there is simply nobody to release.
         */
        override fun onBackgroundEventsFinished() {
            scope.launch { drains.withLock { backgroundEvents.releaseAfter { awaitOutstandingStagings() } } }
        }
    }

    /**
     * Await every staging started since the last drain. Public because two callers need it and neither may reach
     * the list: the background-events handler above (so the OS handler is released after the stagings, capability
     * `receiving-photos`), and the world harness's `stageAllDownloads`, whose operator drives the world synchronously
     * and would otherwise race every download assertion.
     */
    suspend fun awaitOutstandingStagings() {
        outstandingStagings.getAndUpdate { emptyList() }.forEach { it.join() }
    }

    private fun transport(): DownloadTransport = transport ?: newTransport(host).also { transport = it }

    /** The relative staged path of the resource [tag] names — the one place it is derived. */
    private fun relativePath(tag: TaskTag): String = stagingPath(staging.stagingRoot(), tag.ref, tag.resourceKey)

    override suspend fun enqueue(downloads: List<PendingDownload>) {
        downloads.forEach {
            val tag = encodeTag(it.ref, it.resource.resourceKey)
            // Running already: its bytes are on the way; a second transfer would only fetch them twice.
            if (inFlight.containsKey(tag)) return@forEach
            // Queued already: keep its place, take the fresher entry (a re-plan may have re-presigned the url).
            queued[tag] = it
        }
        pump()
    }

    /**
     * Leave / switch: drop the queue and cancel the outstanding transfers. The transport is **not**
     * destroyed — see [DownloadTransport]. Each cancelled task still reports `onCompleted`, which is a
     * no-op here (its entry is already gone) and re-pumps an empty queue.
     */
    override suspend fun cancelAll() {
        queued.clear()
        val outstanding = inFlight.values.toList()
        inFlight.clear()
        outstanding.forEach { it.cancel() }
    }

    /**
     * Called from the core's `handleEventsForBackgroundURLSession` entry: hold the OS [completion] until this wake's
     * stagings are recorded, and realize the transport so its delegate receives the pending events. Returns the
     * handover, through which the wake's owner learns of the release and forwards the operating system's expiry.
     */
    fun adoptBackgroundEvents(completion: () -> Unit): OsCompletions.Handover =
        log.invocation(entryContext, "download.adoptBackgroundEvents") {
            // Logged (law "Absence is never silent"): without it no diagnostic dump could distinguish a wake whose
            // handler was released from one where it was never called.
            //
            // Adopt BEFORE realizing the transport, so every event the realized session delivers — and the drain
            // report that follows them — lands in this handler's window.
            val handover = backgroundEvents.adopt(completion)
            transport() // realize → the session exists with its delegate, so the OS's events are delivered
            handover
        }

    private fun pump() {
        while (inFlight.size < MAX_IN_FLIGHT) {
            val tag = queued.keys.firstOrNull() ?: break
            val next = queued.remove(tag) ?: break
            if (!isFetchableUrl(next.resource.url)) {
                // Pending, not failed: a later reconcile re-presigns the url (`receiving-photos`), and a
                // permanently-bad one is skipped again rather than aborting the process.
                log.w { "skipping unfetchable download url for ${next.resource.resourceKey}" }
                continue
            }
            val task = transport().start(next.resource.url, tag)
            if (task == null) {
                log.w { "transport refused ${next.resource.resourceKey} — left pending for retry" }
                continue
            }
            inFlight[tag] = task
        }
    }
}
