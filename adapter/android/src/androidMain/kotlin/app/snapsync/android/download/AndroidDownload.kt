package app.snapsync.android.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import app.snapsync.model.BeforeListen
import app.snapsync.model.EntryScope
import app.snapsync.model.HandlerSlot
import app.snapsync.model.StartResult
import app.snapsync.model.TransferNetwork
import app.snapsync.model.TransferOutcome
import app.snapsync.model.invocation
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.Completion
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Android [Download] (capability `receiving-photos`): Android's `DownloadManager`, which owns each transfer — it
 * survives this process's death, resumes, retries network errors and 5xx answers on its own, and waits for a network —
 * the counterpart of iOS's background `URLSession`.
 *
 * - **Hidden.** Every request is `VISIBILITY_HIDDEN` (the install-time `DOWNLOAD_WITHOUT_NOTIFICATION`): the member sees
 *   the app's own status line, never a system notification per photo.
 * - **Into the app's external files dir**, never the photo library: DownloadManager cannot write the app's private
 *   storage, and a download written straight into `DCIM` would belong to the download provider and be visible half
 *   done. [DownloadHandlers.onFinished] hands the file to the core, whose staging moves it into the shared area (a copy
 *   across mounts) before this returns.
 * - **The tag rides in the request's description**, the one field DownloadManager keeps with the row, so a transfer a
 *   dead process started is attributed when a later one delivers it.
 * - **Delivery is at-least-once.** A finished row is delivered by the completion broadcast ([DownloadCompleteReceiver],
 *   which starts a dead process), and — because a force-stopped app receives no broadcasts — by the pass every
 *   [listen] runs over the rows still held. A row is removed (which deletes its file) only after `onFinished` has
 *   returned, under one lock, so a broadcast and the pass never deliver one row twice in a process; across processes
 *   staging tolerates a repeat.
 * - **The bytes received are the file's**, not the row's count: a rescheduled running download can end SUCCESSFUL
 *   naming an empty file ([FinishedRow.facts]), and only the owner's length check stops it being staged.
 * - **A raced row is fetched again.** The same reschedule can leave the row naming a file of full length whose tail
 *   was never written (each thread preallocates the whole body before it notices it was stopped), which no length
 *   check sees. Its trace is a twin: the race is the only way one destination gets a second file ([twinsOf]). A
 *   finished row with one is delivered only when the two files are byte-identical — both threads wrote the whole body —
 *   and otherwise restarted here, under the same tag, up to [MAX_RESTARTS] times ([restart]); the owner sees one
 *   transfer that took longer, not a failure that waits for its next reconcile. Past the cap it fails.
 * - **A 403 is final** (an expired presigned link): the row fails, is reported through `onCompleted` with its reason,
 *   and the next reconcile plans the resource again. A link is the backend's stable address, which redirects to a
 *   fresh presign; DownloadManager's own retry goes back to it (measured, decision record `changes/incremental-union`
 *   D2), so a 403 reaches here only when even a fresh presign was refused.
 * - **[cancelAll]** removes every row; DownloadManager broadcasts nothing for a removal, so each is reported here.
 */
class AndroidDownload(
    context: Context,
    private val log: Logger = Logger.withTag("download"),
) : Download {

    private val appContext = context.applicationContext
    private val manager: DownloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val lock = Any()

    private val handlers = HandlerSlot<DownloadHandlers>("Download", BeforeListen.Dropped)

    override fun listen(handlers: DownloadHandlers) {
        this.handlers.set(handlers)
        registration.value = this
        val recovered = deliverFinished(null)
        if (recovered > 0) log.i { "delivered $recovered transfer(s) a previous process left finished" }
    }

    override fun start(url: String, tag: String, network: TransferNetwork): StartResult = runCatchingCancellable {
        manager.enqueue(request(url, tag, restarts = 0, network))
    }.fold(
        onSuccess = { StartResult.Started },
        onFailure = {
            log.w(it) { "$tag: DownloadManager refused the request" }
            StartResult.NotStarted
        },
    )

    /**
     * [url] into a fresh destination, tagged [tag]; the title carries how often the transfer was [restart]ed and the
     * [network] it may use, so a restart keeps the rule the transfer started with.
     *
     * Under [TransferNetwork.UNRESTRICTED_ONLY] (capability `mobile-data`) DownloadManager holds the transfer off every
     * metered network — cellular, a hotspot, a metered Wi-Fi — in its own persisted queue, and runs it once the phone is
     * on an unmetered one, process alive or not (measured on the API 36 emulator, 2026-10-03: held on metered Wi-Fi and
     * cellular, finished within ~6 s of unmetered Wi-Fi). Data Saver needs nothing here: it applies to metered networks
     * only, and DownloadManager already honours it there.
     */
    private fun request(url: String, tag: String, restarts: Int, network: TransferNetwork): DownloadManager.Request =
        DownloadManager.Request(Uri.parse(url))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            .setDestinationInExternalFilesDir(appContext, DIRECTORY, UUID.randomUUID().toString())
            .setAllowedOverMetered(network == TransferNetwork.ANY)
            .setAllowedOverRoaming(network == TransferNetwork.ANY)
            .setDescription(tag)
            .setTitle(
                "$RESTARTS_TITLE$restarts" + if (network == TransferNetwork.UNRESTRICTED_ONLY) UNMETERED_TITLE else "",
            )

    override suspend fun cancelAll() {
        log.invocation(EntryScope.None, "download.cancelAll", result = { cancelled: Int -> "$cancelled transfer(s)" }) {
            cancel()
        }
    }

    private fun cancel(): Int {
        val cancelled = synchronized(lock) {
            rows(
                null,
            ) { cursor -> cursor.long(DownloadManager.COLUMN_ID) to cursor.string(DownloadManager.COLUMN_DESCRIPTION) }
                .onEach { (id, _) -> manager.remove(id) }
        }
        // DownloadManager says nothing about a removal, so every cancelled transfer is reported here, as the port promises.
        cancelled.forEach { (_, tag) -> tag?.let { handlers.orNull("$it's cancel")?.onCompleted(it, "cancelled") } }
        return cancelled.size
    }

    /**
     * One completion broadcast as one wake: [DownloadHandlers.onBackgroundEvents] with [completion], the finished rows
     * delivered (the broadcast's own, and any other a missed broadcast left), then the drain report.
     */
    internal fun deliverBroadcast(id: Long, completion: Completion) =
        log.invocation(
            EntryScope.None,
            "download.handleEvents",
            params = "id=$id",
            result = { "$it finished transfer(s)" },
        ) {
            val current = handlers.orNull("a completion broadcast") ?: return@invocation 0.also { completion.complete() }
            current.onBackgroundEvents(completion)
            deliverFinished(null).also { current.onEventsDrained() }
        }

    /** Deliver every finished row still held (only [id]'s when given), each removed once delivered. Answers how many. */
    private fun deliverFinished(id: Long?): Int = synchronized(lock) {
        val current = handlers.orNull("a finished transfer") ?: return 0
        val finished = rows(id) { it.finishedRow() }.filterNotNull()
        for (row in finished) {
            runCatchingCancellable {
                log.invocation(
                    EntryScope.None,
                    "download.didComplete",
                    params = "tag=${row.tag}",
                    severity = Severity.Debug,
                ) {
                    row.deliverTo(current)
                }
            }
                .onFailure { log.w(it) { "${row.tag}: delivering the finished transfer failed" } }
            manager.remove(row.id)
        }
        finished.size
    }

    private fun FinishedRow.deliverTo(handlers: DownloadHandlers) {
        if (succeeded) {
            val path = localPath
            if (path == null) {
                handlers.onCompleted(tag, "DownloadManager reported success with no file")
                return
            }
            val file = File(path)
            val twins = twinsOf(file)
            val raced = file.name != file.name.take(UUID_LENGTH) || twins.isNotEmpty()
            val trusted = !raced || twins.isNotEmpty() && twins.all { it.readBytes().contentEquals(file.readBytes()) }
            // The stopped thread leaves its file behind; the row's own goes with the row.
            twins.forEach { it.delete() }
            if (!trusted) {
                restart(handlers)
                return
            }
            handlers.onFinished(tag, facts(file.length()), path)
            handlers.onCompleted(tag, null)
        } else {
            handlers.onCompleted(tag, "download failed (reason $reason)")
        }
    }

    /**
     * The files beside [file] that its row's other thread claimed. Every destination is a fresh UUID ([start]), and the
     * provider claims `<uuid>-N` only when `<uuid>` already exists — which, for a fresh UUID, only the row's other thread
     * makes. So a row named `-N`, or one beside a twin, was raced, whichever file it names. Whenever the row can name a
     * file its successful thread did not write, both files exist by the time it reads SUCCESSFUL: that thread wrote into
     * the other's file, which had to be claimed first. A twin the stopped thread deletes (it does on an error status) is
     * gone with the row's file truncated to empty, which [FinishedRow.facts] reports short.
     */
    private fun twinsOf(file: File): List<File> {
        val destination = file.name.take(UUID_LENGTH)
        return file.parentFile?.listFiles { it.name.startsWith(destination) && it.name != file.name }.orEmpty().toList()
    }

    /**
     * Fetch a raced row's [FinishedRow.url] again into a fresh destination under the same tag, so the owner's transfer
     * stays in flight; the raced row is removed by the caller. Past [MAX_RESTARTS], or when DownloadManager refuses the
     * request, the transfer fails and the next reconcile plans it again.
     */
    private fun FinishedRow.restart(handlers: DownloadHandlers) {
        val url = url
        if (url == null || restarts >= MAX_RESTARTS) {
            log.w { "$tag: DownloadManager ran the transfer twice, $restarts restart(s) already; refusing its file" }
            handlers.onCompleted(tag, "DownloadManager ran the transfer twice; its file cannot be trusted")
            return
        }
        runCatchingCancellable { manager.enqueue(request(url, tag, restarts + 1, network)) }.fold(
            onSuccess = { log.w { "$tag: DownloadManager ran the transfer twice; fetching it again (${restarts + 1})" } },
            onFailure = {
                log.w(it) { "$tag: DownloadManager refused the restart" }
                handlers.onCompleted(tag, "DownloadManager ran the transfer twice and refused its restart")
            },
        )
    }

    /** Each of this app's rows ([id]'s alone when given), mapped by [read]. DownloadManager answers only this app's. */
    private fun <T> rows(id: Long?, read: (Cursor) -> T): List<T> {
        val query = DownloadManager.Query().apply { if (id != null) setFilterById(id) }
        return manager.query(query)?.use { cursor -> buildList { while (cursor.moveToNext()) add(read(cursor)) } }
            ?: emptyList()
    }

    private fun Cursor.finishedRow(): FinishedRow? {
        val status = int(DownloadManager.COLUMN_STATUS)
        if (status != DownloadManager.STATUS_SUCCESSFUL && status != DownloadManager.STATUS_FAILED) return null
        val tag = string(DownloadManager.COLUMN_DESCRIPTION) ?: return null
        return FinishedRow(
            id = long(DownloadManager.COLUMN_ID),
            tag = tag,
            succeeded = status == DownloadManager.STATUS_SUCCESSFUL,
            reason = int(DownloadManager.COLUMN_REASON),
            localPath = string(DownloadManager.COLUMN_LOCAL_URI)?.let { Uri.parse(it).path },
            total = long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
            received = long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            url = string(DownloadManager.COLUMN_URI),
            restarts = string(DownloadManager.COLUMN_TITLE)?.removePrefix(RESTARTS_TITLE)?.removeSuffix(UNMETERED_TITLE)
                ?.toIntOrNull() ?: 0,
            network = if (string(DownloadManager.COLUMN_TITLE)?.endsWith(UNMETERED_TITLE) == true) {
                TransferNetwork.UNRESTRICTED_ONLY
            } else {
                TransferNetwork.ANY
            },
        )
    }

    private class FinishedRow(
        val id: Long,
        val tag: String,
        val succeeded: Boolean,
        val reason: Int,
        val localPath: String?,
        val total: Long,
        val received: Long,
        val url: String?,
        val restarts: Int,
        val network: TransferNetwork,
    ) {
        /**
         * The facts of a successful row whose file holds [onDisk] bytes. The bytes received are the FILE's, never the
         * row's count: when DownloadManager reschedules a running download (its BOOT_COMPLETED pass does, for every
         * row), the stopped thread runs on beside its successor, each claims its own file (`X`, then `X-1`), and the
         * row can end SUCCESSFUL naming the empty one while the body sits in the other — measured on API 36. A file
         * shorter than what the row says arrived is declared at least that long, since the row's total is rewritten
         * by every writer's close; the owner's length check then refuses it and the next reconcile fetches it again.
         */
        fun facts(onDisk: Long): TransferOutcome =
            TransferOutcome(HTTP_OK, if (onDisk < received) maxOf(total, received) else total, onDisk)
    }

    internal companion object {
        /** The subdirectory of the app's external files dir the transfers land in. */
        const val DIRECTORY = "downloads"
        const val HTTP_OK = 200

        /** The length of a destination's name: a UUID's canonical form. */
        const val UUID_LENGTH = 36

        /** How often a raced transfer is fetched again before it fails. */
        const val MAX_RESTARTS = 3

        /** The title prefix that counts a row's restarts; the title is the one other field DownloadManager keeps. */
        const val RESTARTS_TITLE = "restarts:"

        /** The title suffix of a transfer held to unmetered networks, which a restart keeps (capability `mobile-data`). */
        const val UNMETERED_TITLE = " unmetered"

        /** The adapter the process's composition listened on last; a completion broadcast waits for one. */
        val registration = MutableStateFlow<AndroidDownload?>(null)
    }
}

private fun Cursor.string(column: String): String? = getString(getColumnIndexOrThrow(column))
private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))

/**
 * `DownloadManager`'s completion broadcast — an entry port. Declared in the manifest, so it reaches a dead process:
 * Android starts it, `Application.onCreate` composes (building no UI), and this hands the finished row to the
 * [AndroidDownload] the composition listened on. No composition within [COMPOSITION_WAIT_MILLIS] — a build that refused
 * at start, or a rig launch whose downloads are mocked — delivers nothing; the row stays for the next start's pass.
 *
 * The broadcast is held (`goAsync`) until the core releases its completion — once the delivered files are staged — or
 * until [BROADCAST_BUDGET_MILLIS], a background broadcast's allowance, whichever comes first. The broadcast has no
 * expiry signal of its own: like a background `URLSession` relaunch, its only "time is up" is the process's background
 * time, which the core holds across the wake.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        val pending = goAsync()
        scope.launch {
            val released = CompletableDeferred<Unit>()
            val download = withTimeoutOrNull(COMPOSITION_WAIT_MILLIS) {
                AndroidDownload.registration.filterNotNull().first()
            }
            if (download != null) {
                runCatchingCancellable { download.deliverBroadcast(id, OnceCompletion { released.complete(Unit) }) }
                    .onFailure { Logger.withTag("download").w(it) { "the completion broadcast for $id failed" } }
                withTimeoutOrNull(BROADCAST_BUDGET_MILLIS) { released.await() }
            }
            pending.finish()
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        const val COMPOSITION_WAIT_MILLIS = 10_000L

        /** How long the broadcast is held at most: under a background broadcast's minute before it counts as stuck. */
        const val BROADCAST_BUDGET_MILLIS = 50_000L
    }
}

/** A [Completion] released once, with no expiry signal of its own ([onExpired] never runs). */
internal class OnceCompletion(private val release: () -> Unit) : Completion {
    private val done = AtomicBoolean(false)

    override fun complete() {
        if (done.compareAndSet(false, true)) release()
    }

    override fun onExpired(action: () -> Unit) = Unit
}
