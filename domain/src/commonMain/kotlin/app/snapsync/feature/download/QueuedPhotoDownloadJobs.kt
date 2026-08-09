package app.snapsync.feature.download

import app.snapsync.ports.BackgroundEventsReceipts
import app.snapsync.ports.DownloadTask
import app.snapsync.ports.DownloadTransport
import app.snapsync.ports.DownloadTransportHost
import app.snapsync.ports.PhotoDownloadJobs
import app.snapsync.ports.TransferOutcome

import app.snapsync.ports.AssetRef
import app.snapsync.ports.LogScope
import app.snapsync.ports.PendingDownload
import app.snapsync.ports.ReceiptDeadlines
import app.snapsync.ports.invocation
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

/** Where a resource's bytes land in durable App-Group staging. `/` is not legal in a path segment. */
internal fun stagingPath(root: String, ref: AssetRef, resourceKey: String): String =
    "$root/${ref.sourceDeviceId.replace('/', '_')}/${resourceKey.replace('/', '_')}"

/**
 * Whether a finished transfer's bytes may be staged (capability `photo-download`).
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
 * The iOS [PhotoDownloadJobs] (capability `photo-download`), platform-free: a pending queue drained
 * through a bounded in-flight window into a [DownloadTransport] (on iOS a background `URLSession`, in
 * tests a fake). Each finished transfer is staged durably and reported via [onStaged]; the window refills
 * as transfers complete.
 *
 * **Cancellation cancels tasks, never the transport.** [cancelAll] drops the queue and cancels each
 * outstanding [DownloadTask]; the transport survives, so the next reconcile after a leave/switch enqueues
 * normally. Destroying the transport instead is what aborted the app in production — see
 * [DownloadTransport]. A transport the *system* invalidates is discarded and rebuilt on the next transfer.
 */
class QueuedPhotoDownloadJobs(
    private val scope: CoroutineScope,
    private val stagingRoot: String,
    private val newTransport: (DownloadTransportHost) -> DownloadTransport,
    // Where this session's OS completion handler is released (capability `ios-app-shell`). UIKit owns
    // that handler and requires the main thread; the drain that triggers the release arrives on a
    // session-owned queue, so the lane is the only thing putting it where it belongs. The default is
    // reached ONLY by direct construction in unit tests: both compositions pass one — the app its main
    // lane, the world harness its own composition lane.
    private val uiLane: CoroutineContext = EmptyCoroutineContext,
    private val log: Logger = Logger.withTag("PhotoDownloadJobs"),
    // The ambient entry-point prefix, so every line a background-events wake causes traces back to it.
    private val logScope: LogScope = LogScope.NoOp,
) : PhotoDownloadJobs {

    /**
     * Set by the composition root after the controller exists: deliver a staged resource.
     *
     * `suspend`, and launched HERE rather than by the composition, so this class can track the import
     * it starts. The composition's former `scope.launch { … }` handed the work to the app scope and kept
     * no handle, which is why [onBackgroundEventsFinished] had nothing to wait for and released the OS
     * handler while the imports it announced were merely queued.
     */
    var onStaged: (suspend (AssetRef, resourceKey: String, stagedPath: String) -> Unit)? = null

    /**
     * The OS completion handlers of this session's background-events wakes (capability `ios-app-shell`).
     *
     * This used to be a single mutable field holding the raw handler. It awaited the imports honestly —
     * which the upload tier's equivalent did not — but it did so with **no bound at all**: an import that
     * never reported left the handler unanswered for the process's life, and an unanswered handler costs
     * the app the very download wakes this capability depends on. A single slot also silently overwrote
     * an earlier wake's handler rather than releasing it.
     */
    private val backgroundEvents = BackgroundEventsReceipts(
        scope = scope,
        entryPoint = "download.onBackgroundSessionEvents",
        deadline = ReceiptDeadlines.BACKGROUND_EVENTS,
        work = { awaitOutstandingImports() },
        releaseLane = uiLane,
        log = log,
    )

    /**
     * The imports started by [DownloadTransportHost.onStaged] since the last drain. Held so the OS's
     * background-events handler can be released *after* them (capability `photo-download`) — the
     * session reports its own events drained, which says nothing about the imports they caused.
     */
    private val outstandingImports = mutableListOf<Job>()

    private val queued = ArrayDeque<PendingDownload>()

    /** The bounded window, keyed by transfer description — which also makes a re-enqueue idempotent. */
    private val inFlight = LinkedHashMap<String, DownloadTask>()

    private var transport: DownloadTransport? = null

    private val host = object : DownloadTransportHost {
        override fun accepts(description: String, outcome: TransferOutcome): Boolean {
            val staged = outcome.mayBeStaged()
            // Logged for EVERY finished transfer, not only rejections (capability `diagnostic-logging`).
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
            return stagingPath(stagingRoot, tag.ref, tag.resourceKey)
        }

        override fun onStaged(description: String, stagedPath: String) {
            val tag = decodeTag(description) ?: return
            val deliver = onStaged ?: return
            // Launched here, and REMEMBERED: this fires on the transport's delegate queue, which must
            // not be blocked by an import, but the job has to remain reachable so the wake's OS handler
            // can wait for it. Pruning completed jobs keeps the list from growing across a long session.
            outstandingImports.removeAll { it.isCompleted }
            outstandingImports += scope.launch { deliver(tag.ref, tag.resourceKey, stagedPath) }
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
         * The session has delivered every event it had. That is NOT the same as the app being done:
         * each delivery started an import, and those are what the OS handler is really reporting on
         * (capability `photo-download`). So join them first, then release — which the receipts do,
         * bounded, and for every handler outstanding rather than only the most recent one.
         *
         * Unconditional now: a foreground drain has no handler waiting on it, but the imports it
         * announces are joined all the same, and the receipts simply have nobody to release.
         */
        override fun onBackgroundEventsFinished() = backgroundEvents.drained()
    }

    /**
     * Await every import started since the last drain. Public because two callers need it and neither may
     * reach the list: the background-events handler above (so the OS handler is released after the
     * imports, capability `photo-download`), and the world harness's `stageAllDownloads`, whose operator
     * drives the world synchronously and would otherwise race every download assertion.
     */
    suspend fun awaitOutstandingImports() {
        val pending = outstandingImports.toList()
        outstandingImports.clear()
        pending.forEach { it.join() }
    }

    private fun transport(): DownloadTransport = transport ?: newTransport(host).also { transport = it }

    override suspend fun enqueue(downloads: List<PendingDownload>) {
        downloads.forEach { queued.addLast(it) }
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
     * Called from the Swift host's `handleEventsForBackgroundURLSession`: realize the transport so its
     * delegate receives the pending events, and store the completion handler to call once they drain.
     */
    fun adoptBackgroundEvents(completion: () -> Unit): Unit = log.invocation(logScope, "download.adoptBackgroundEvents") {
        // Logged, because it was not (law "Absence is never silent"): this call wrote nothing at all, so
        // no diagnostic dump could distinguish a wake whose handler was released from one where it was
        // never called — while the upload tier's equivalent was measurable line by line.
        //
        // Adopt BEFORE realizing the transport, so the realize is inside the bound: a session that never
        // reports is exactly what the deadline exists for. (The clock starts a dispatch later, not on
        // this line — see `BackgroundEventsReceipts`.)
        backgroundEvents.adopt(completion)
        transport() // realize → the session exists with its delegate, so the OS's events are delivered
        // Explicitly `Unit`. `invocation` returns its block's value, so without this the realized
        // transport becomes this function's return type — an internal handle appearing in an exported,
        // ObjC-visible signature for no reason.
        Unit
    }

    private fun pump() {
        while (inFlight.size < MAX_IN_FLIGHT) {
            val next = queued.removeFirstOrNull() ?: break
            val tag = encodeTag(next.ref, next.resource.resourceKey)
            if (inFlight.containsKey(tag)) continue // already transferring this resource — idempotent
            if (!isFetchableUrl(next.resource.url)) {
                // Pending, not failed: a later reconcile re-presigns the url (`photo-download`), and a
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
