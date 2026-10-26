package app.snapsync.services.downloads

import app.snapsync.data.crypto.DownloadOpening
import app.snapsync.data.crypto.Landing
import app.snapsync.data.crypto.OpenAnswer
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.model.AssetRef
import app.snapsync.model.ConfinedTo
import app.snapsync.model.PendingDownload
import app.snapsync.model.roleFromUploadKey
import app.snapsync.ports.Download
import app.snapsync.ports.StartResult
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.TransferOutcome
import app.snapsync.services.staging.StagingService
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/** Bounded in-flight window (Apple: keep background tasks in the low hundreds; we stay well under). */
internal const val MAX_IN_FLIGHT = 24

/** taskDescription field separator — a newline cannot occur in device ids / sanitized keys / filenames. */
private const val SEP = "\n"

/**
 * What a transfer's opaque description decodes back to. [eventId] is the event whose bytes the transfer fetches; it
 * is `""` for a transfer a build before change `event-scoped-local-state` started, whose tag has three fields — the OS
 * holds a tag across an update, so both shapes decode.
 */
internal data class TaskTag(val ref: AssetRef, val resourceKey: String, val eventId: String)

internal fun encodeTag(ref: AssetRef, resourceKey: String, eventId: String): String =
    listOf(ref.sourceDeviceId, ref.sourceAssetId, resourceKey, eventId).joinToString(SEP)

internal fun decodeTag(description: String): TaskTag? {
    val parts = description.split(SEP)
    if (parts.size !in LEGACY_TAG_FIELDS..TAG_FIELDS) return null
    val device = DeviceId.parse(parts[0]) ?: return null
    val asset = AssetId.parse(parts[1]) ?: return null
    return TaskTag(AssetRef(device, asset), parts[2], parts.getOrElse(LEGACY_TAG_FIELDS) { "" })
}

/** A tag's fields: source device, source asset, resource key, event. */
private const val TAG_FIELDS = 4

/** A tag started before transfers named their event: the same fields, without the event. */
private const val LEGACY_TAG_FIELDS = 3

/**
 * Where a resource's bytes land in durable staging, relative to the shared area: under its event, so one event's
 * staged bytes are one folder. A transfer with no event (a pre-`event-scoped-local-state` tag) stages where it always
 * did. `/` is not legal in a path segment: a device id never holds one, and a key or an event may.
 */
internal fun stagingPath(root: String, eventId: String, ref: AssetRef, resourceKey: String): String {
    val leaf = "${ref.sourceDeviceId.value}/${resourceKey.replace('/', '_')}"
    return if (eventId.isEmpty()) "$root/$leaf" else "$root/${eventId.replace('/', '_')}/$leaf"
}

/**
 * Whether a finished transfer's bytes may be staged.
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
    // Read once: a property of another module's class is re-read, and null-checked, at each use.
    val status = statusCode
    if (status != null && status !in 200..299) return false
    if (expectedBytes >= 0 && receivedBytes < expectedBytes) return false
    return true
}

/**
 * A download link the background session can actually fetch — the backend's stable per-resource address, which
 * redirects to a freshly presigned S3 link per attempt (decision record `changes/incremental-union`, D1). `NSURL` happily parses a hostless or
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
 * The download jobs, platform-free: a pending queue drained through a bounded
 * in-flight window into the [Download] port (on iOS a background `URLSession`, in tests a double). What the port
 * reports arrives through the composition's entry points, which call [onFinished] and [onCompleted]; a relaunch for
 * the session's events waits for the stagings they caused ([awaitOutstandingStagings]). The window refills as transfers
 * complete.
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
class DownloadJobs(
    /**
     * Where staged bytes live: a relative root, and the move that keeps a finished body there. The staged path this
     * class reports is the RELATIVE one, so the download store never holds an absolute container path (a restored
     * device's container may move).
     */
    private val staging: StagingService,
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
    /**
     * The networks a download started NOW may use — read as each one starts, so a change of
     * the member's choice governs only the downloads that start after it.
     */
    private val network: () -> TransferNetwork,
    /**
     * The headers every download carries (`BackendExchange.versionHeaders`): the version the app declares on every
     * request — the download redirect's too (`api/openapi.yaml`, `downloadResource`). Read per transfer.
     */
    private val headers: () -> Map<String, String>,
    /**
     * How an encrypted event's download is opened before it is staged (the encrypted file format,
     * `docs/architecture.md`); `null` where nothing is ever sealed.
     */
    private val opening: DownloadOpening? = null,
    private val log: Logger = Logger.withTag("DownloadJobs"),
) {

    /**
     * The stagings [onFinished] started since the last drain. Held so the session's drain report — which hands in the
     * relaunch's completion — is answered *after* they are recorded: the session reports its own events drained,
     * which says nothing about the store writes they caused.
     *
     * A thread-safe cell, not a plain list (law "State reached from OS callbacks is confined", `docs/architecture.md`):
     * [onFinished] registers from the session's delegate queue while [awaitOutstandingStagings] takes the list from a
     * coroutine, and a plain list shared between them could drop a registration — releasing the OS handler before a
     * staging it announced — or throw mid-iteration.
     */
    private val outstandingStagings = MutableStateFlow<List<Job>>(emptyList())

    /**
     * The not-yet-started transfers, keyed by tag like [inFlight] — so a key is queued at most once, and a key is
     * never both queued and in flight ([enqueue] is idempotent).
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
     * of it — before this returns, because the platform deletes the file when it does. The store write it causes is
     * started through [start] — the delivering entry point's, so its `done` waits for the write and what follows it.
     */
    fun onFinished(tag: String, facts: TransferOutcome, tempPath: String, start: (suspend () -> Unit) -> Job) {
        val staged = facts.mayBeStaged()
        // Logged for EVERY finished transfer, not only rejections: `expected=-1` means
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
        // An encrypted event's bytes are taken over beside their staging path — the platform deletes its file when this
        // returns — and opened INTO it off the delegate queue: only a file whose every byte authenticated is staged.
        val landing = when (opening) {
            null -> Landing.Plain(relative)
            else -> opening.landing(relative)
        }
        if (!staging.stage(tempPath, landing.stagedAt)) return
        // Launched here, and REMEMBERED: this runs on the session's delegate queue, which must not wait for a store
        // write, but the write has to stay reachable so the wake's OS handler can wait for it. Pruning completed jobs
        // keeps the list from growing across a long session.
        val recording = start {
            // A file that does not open stays unstaged, so the next reconcile downloads it again.
            if (opened(landing, decoded) == OpenAnswer.NotOpened) return@start
            onStaged(decoded.ref, decoded.resourceKey, relative)
        }
        outstandingStagings.update { held -> held.filterNot { it.isCompleted } + recording }
    }

    /**
     * [tag]'s transfer ended — finished, failed or cancelled. Its slot is free, and the next transfer is started through
     * [start] — the delivering entry point's, so its `done` waits for it.
     */
    fun onCompleted(tag: String, error: String?, start: (suspend () -> Unit) -> Job) {
        if (error != null) log.w { "download task failed (will retry): $error" }
        start {
            inFlight.remove(tag)
            pump()
        }
    }

    /**
     * Await every staging started since the last drain. Public because two callers need it and neither may reach
     * the list: the drain report's entry point (so the relaunch's completion is answered after the stagings), and
     * the world harness's `stageAllDownloads`, whose operator drives the world synchronously and would otherwise race
     * every download assertion.
     */
    suspend fun awaitOutstandingStagings() {
        outstandingStagings.getAndUpdate { emptyList() }.forEach { it.join() }
    }

    /** [landing] opened into its path when it is sealed — a file that does not open is not staged. */
    private suspend fun opened(landing: Landing, tag: TaskTag): OpenAnswer = when (opening) {
        null -> OpenAnswer.Opened
        else -> opening.open(
            landing,
            tag.ref.sourceDeviceId,
            tag.ref.sourceAssetId,
            roleFromUploadKey(tag.resourceKey),
            EventId.parse(tag.eventId),
        )
    }

    /** The relative staged path of the resource [tag] names — the one place it is derived. */
    private fun relativePath(
        tag: TaskTag,
    ): String = stagingPath(staging.stagingRoot(), tag.eventId, tag.ref, tag.resourceKey)

    /** Enqueue downloads for the given not-yet-staged resources (idempotent; already-running keys are skipped). */

    suspend fun enqueue(downloads: List<PendingDownload>) {
        downloads.forEach {
            val tag = encodeTag(it.ref, it.resource.resourceKey, it.eventId)
            // Running already: its bytes are on the way; a second transfer would only fetch them twice.
            if (tag in inFlight) return@forEach
            // Queued already: keep its place, take the fresher entry (a re-plan may carry a different url).
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
    suspend fun cancelAll() {
        queued.clear()
        inFlight.clear()
        download.cancelAll()
    }

    private fun pump() {
        while (inFlight.size < MAX_IN_FLIGHT) {
            val first = queued.entries.firstOrNull() ?: break
            val tag = first.key
            val next = first.value
            queued.remove(tag)
            if (!isFetchableUrl(next.resource.url)) {
                // Pending, not failed: a later reconcile re-plans the url, and a
                // permanently-bad one is skipped again rather than aborting the process.
                log.w { "skipping unfetchable download url for ${next.resource.resourceKey}" }
                continue
            }
            when (download.start(next.resource.url, headers(), tag, network())) {
                StartResult.Started -> inFlight += tag
                StartResult.NotStarted -> log.w { "the download was not started for ${next.resource.resourceKey} — left pending" }
            }
        }
    }
}
