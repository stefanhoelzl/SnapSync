package app.snapsync.feature.download

import app.snapsync.model.ConfinedTo
import app.snapsync.ports.Completion
import app.snapsync.services.wake.OsCompletions
import app.snapsync.model.StartResult
import app.snapsync.ports.Download
import app.snapsync.ports.PhotoDownloadJobs
import app.snapsync.ports.StagedBytes
import app.snapsync.model.TransferOutcome

import app.snapsync.model.AssetRef
import app.snapsync.ports.EntryContext
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
 * The [PhotoDownloadJobs] (capability `receiving-photos`), platform-free: a pending queue drained through a bounded
 * in-flight window into the [Download] port (on iOS a background `URLSession`, in tests a double). What the port
 * reports arrives through the composition's handlers, which call [onFinished], [onCompleted], [onInvalidated],
 * [adoptBackgroundEvents] and [onBackgroundEventsFinished]; the window refills as transfers complete.
 *
 * **A finished body is judged and kept here, inline.** The port reports the facts and a temporary file the platform
 * deletes when its callback returns; whether it may be staged, where, and the move into staging all happen on that
 * callback ([onFinished]) — a once-only delivery persisted before it returns. The store write the staging causes is
 * launched and joined before the wake's OS handler is released.
 *
 * **Cancellation cancels transfers, never the session.** [cancelAll] drops the queue and asks the port to cancel
 * every transfer it holds — a relaunched process's inherited ones included; the session survives, so the next
 * reconcile after a leave/switch enqueues normally.
 */
class QueuedPhotoDownloadJobs(
    private val scope: CoroutineScope,
    /**
     * Where staged bytes live: a relative root, and the move that keeps a finished body there. The staged path this
     * class reports is the RELATIVE one, so the download store never holds an absolute container path (a restored
     * device's container may move).
     */
    private val staging: StagedBytes,
    /** The platform's downloads. */
    private val download: Download,
    /**
     * Record a staged resource. Required, and bound at construction (law "Callbacks are bound at construction",
     * `docs/architecture.md`): a process the OS relaunched only to deliver download-session events builds the jobs
     * and nothing else, and every staged resource must still reach the controller.
     *
     * `suspend`, and launched HERE rather than by the composition, so this class can track the staging it starts:
     * the wake's OS handler is released only once every staging it announced is recorded. The import that follows is
     * not this callback's — it is the process tail's first unit.
     */
    private val onStaged: suspend (AssetRef, resourceKey: String, stagedPath: String) -> Unit,
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
     * handed by [adoptBackgroundEvents]. The adapter's completion puts the release on the thread UIKit requires.
     */
    private val backgroundEvents = OsCompletions(entryPoint = "download.onBackgroundSessionEvents", log = log)

    /**
     * Serialises the drains: taking the outstanding stagings is a **destructive** read, so a second drain overlapping
     * the first would find the list empty and release its handlers against stagings that are still being recorded.
     */
    private val drains = Mutex()

    /**
     * The stagings [onFinished] started since the last drain. Held so the OS's background-events handler is released
     * *after* they are recorded (capability `receiving-photos`) — the session reports its own events drained, which
     * says nothing about the store writes they caused.
     *
     * A thread-safe cell, not a plain list (law "State reached from OS callbacks is confined", `docs/architecture.md`):
     * [onFinished] registers from the session's delegate queue while [awaitOutstandingStagings] takes the list from a
     * coroutine, and a plain list shared between them could drop a registration — releasing the OS handler before a
     * staging it announced — or throw mid-iteration.
     */
    private val outstandingStagings = MutableStateFlow<List<Job>>(emptyList())

    /**
     * The not-yet-started transfers, keyed by tag like [inFlight] — so a key is queued at most once, and a key is
     * never both queued and in flight ([PhotoDownloadJobs.enqueue] is idempotent).
     *
     * This was an `ArrayDeque`, and [enqueue] appended every download it was handed. Every reconcile hands over its
     * WHOLE pending snapshot, so a second reconcile while a backlog was still queued doubled it. Measured on the SE2
     * (S2 bench, 2026-09-24): 136 transfers for 100 resources — and the stale copies then held the window, so the next
     * reconcile's new photos sat in this in-memory queue with no transfer behind them, and a SIGKILL lost them while
     * the OS finished only the stale copies (a cold relaunch staged 24, imported 0).
     */
    @ConfinedTo("composition")
    private val queued = LinkedHashMap<String, PendingDownload>()

    /** The bounded window, by tag — which also makes a re-enqueue idempotent. */
    @ConfinedTo("composition")
    private val inFlight = LinkedHashSet<String>()

    /**
     * A finished transfer, delivered **inline** on the session's delegate queue with the platform's temporary file.
     *
     * A finished transfer is not a good transfer: `URLSession` delivers an HTTP error as a *successful* transfer of
     * the error body. So it is judged first ([mayBeStaged]) — staging is what makes these bytes the store's truth, and a
     * staged error body would be imported, fail, and be retried forever, never re-downloaded. Then it is moved to the
     * staging path derived from [tag] alone — a relaunched process never started the transfer and remembers nothing
     * of it — before this returns, because the platform deletes the file when it does.
     */
    fun onFinished(tag: String, facts: TransferOutcome, tempPath: String) {
        val staged = facts.mayBeStaged()
        // Logged for EVERY finished transfer, not only rejections (capability `privacy-security`): `expected=-1` means
        // the server sent no `Content-Length`, so the length check is inert and the body is admitted on status alone.
        log.i {
            "transfer finished: status=${facts.statusCode} expected=${facts.expectedBytes} " +
                "received=${facts.receivedBytes} → ${if (staged) "stage" else "REJECT (will re-download)"}"
        }
        // A rejection leaves the resource un-staged, so the next reconcile re-downloads it; the completion that follows
        // frees its slot either way.
        if (!staged) return
        val decoded = decodeTag(tag) ?: run {
            log.w { "finished bytes carry an undecodable transfer tag — not staged: $tag" }
            return
        }
        val relative = relativePath(decoded)
        if (!staging.stage(tempPath, relative)) return
        // Launched here, and REMEMBERED: this runs on the session's delegate queue, which must not wait for a store
        // write, but the write has to stay reachable so the wake's OS handler can wait for it. Pruning completed jobs
        // keeps the list from growing across a long session.
        val recording = scope.launch { onStaged(decoded.ref, decoded.resourceKey, relative) }
        outstandingStagings.update { held -> held.filterNot { it.isCompleted } + recording }
    }

    /** [tag]'s transfer ended — finished, failed or cancelled. Its slot is free. */
    fun onCompleted(tag: String, error: String?) {
        if (error != null) log.w { "download task failed (will retry): $error" }
        scope.launch {
            inFlight.remove(tag)
            pump()
        }
    }

    /**
     * The **system** invalidated the session (we never do): its transfers are gone, and the port runs the next on a
     * fresh one. The window empties and refills.
     */
    fun onInvalidated() {
        log.w { "the download session was invalidated by the system — continuing on a fresh one" }
        scope.launch {
            inFlight.clear()
            pump()
        }
    }

    /**
     * The session has delivered every event it had. That is not yet the wake's own work done: each delivery started a
     * staging, and recording those is what the OS handler reports on (capability `receiving-photos`). So join them
     * first, then release every handler outstanding — never waiting for the imports, which are the tail's.
     *
     * Unconditional: a foreground drain has no handler waiting on it, but the stagings it announces are joined all the
     * same, and there is simply nobody to release.
     */
    fun onBackgroundEventsFinished() {
        scope.launch { drains.withLock { backgroundEvents.releaseAfter { awaitOutstandingStagings() } } }
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

    /** The relative staged path of the resource [tag] names — the one place it is derived. */
    private fun relativePath(tag: TaskTag): String = stagingPath(staging.stagingRoot(), tag.ref, tag.resourceKey)

    override suspend fun enqueue(downloads: List<PendingDownload>) {
        downloads.forEach {
            val tag = encodeTag(it.ref, it.resource.resourceKey)
            // Running already: its bytes are on the way; a second transfer would only fetch them twice.
            if (tag in inFlight) return@forEach
            // Queued already: keep its place, take the fresher entry (a re-plan may have re-presigned the url).
            queued[tag] = it
        }
        pump()
    }

    /**
     * Leave / switch: drop the queue and cancel every transfer the session holds — this process's and any a relaunched
     * process inherited, which a leave must stop as surely as its own. Returns once each is cancelled, so the caller's
     * prune follows it; each still reports [onCompleted], a no-op here (its slot is already gone) that re-pumps an
     * empty queue. A finish that raced the cancel stages bytes whose row the prune then drops: the controller finds
     * no row to record them against and discards them.
     */
    override suspend fun cancelAll() {
        queued.clear()
        inFlight.clear()
        download.cancelAll()
    }

    /**
     * Called from the download session's background-events handler: hold the OS [completion] until this wake's
     * stagings are recorded. Returns the handover, through which the wake's owner learns of the release and forwards
     * the operating system's expiry. The adapter brings its session up after handing this over, so every event it then
     * delivers — and the drain report that follows them — lands in this handler's window.
     */
    fun adoptBackgroundEvents(completion: Completion): OsCompletions.Handover =
        log.invocation(entryContext, "download.adoptBackgroundEvents") {
            // Logged (law "Absence is never silent"): without it no diagnostic dump could distinguish a wake whose
            // handler was released from one where it was never called.
            backgroundEvents.adopt(completion)
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
            when (download.start(next.resource.url, tag)) {
                StartResult.Started -> inFlight += tag
                StartResult.NotStarted -> log.w { "the download was not started for ${next.resource.resourceKey} — left pending" }
            }
        }
    }
}
