package app.snapsync.feature.download

import app.snapsync.ports.DownloadTask
import app.snapsync.ports.DownloadTransport
import app.snapsync.ports.DownloadTransportHost
import app.snapsync.ports.PhotoDownloadJobs
import app.snapsync.ports.TransferOutcome

import app.snapsync.ports.AssetRef
import app.snapsync.ports.PendingDownload
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    private val log: Logger = Logger.withTag("PhotoDownloadJobs"),
) : PhotoDownloadJobs {

    /** Set by the composition root after the controller exists: deliver a staged resource. */
    var onStaged: ((AssetRef, resourceKey: String, stagedPath: String) -> Unit)? = null

    /** Stored when the OS relaunches the app for background events; invoked when they drain. */
    private var backgroundCompletion: (() -> Unit)? = null

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
            onStaged?.invoke(tag.ref, tag.resourceKey, stagedPath)
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

        override fun onBackgroundEventsFinished() {
            val completion = backgroundCompletion ?: return
            backgroundCompletion = null
            scope.launch { completion() }
        }
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
    fun adoptBackgroundEvents(completion: () -> Unit) {
        backgroundCompletion = completion
        transport() // realize → the session exists with its delegate, so the OS's events are delivered
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
