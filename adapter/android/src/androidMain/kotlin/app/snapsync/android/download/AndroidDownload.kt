package app.snapsync.android.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import app.snapsync.model.StartResult
import app.snapsync.model.TransferOutcome
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.Completion
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import co.touchlab.kermit.Logger
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
 * - **A 403 is final** (an expired presigned link): the row fails, is reported through `onCompleted` with its reason,
 *   and the next reconcile plans the resource with a fresh link.
 * - **[cancelAll]** removes every row; DownloadManager broadcasts nothing for a removal, so each is reported here.
 */
class AndroidDownload(
    context: Context,
    private val log: Logger = Logger.withTag("download"),
) : Download {

    private val appContext = context.applicationContext
    private val manager: DownloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val lock = Any()

    @Volatile
    private var handlers: DownloadHandlers? = null

    override fun listen(handlers: DownloadHandlers) {
        this.handlers = handlers
        registration.value = this
        val recovered = deliverFinished(null)
        if (recovered > 0) log.i { "delivered $recovered transfer(s) a previous process left finished" }
    }

    override fun start(url: String, tag: String): StartResult = runCatchingCancellable {
        val request = DownloadManager.Request(Uri.parse(url))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            .setDestinationInExternalFilesDir(appContext, DIRECTORY, UUID.randomUUID().toString())
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDescription(tag)
        manager.enqueue(request)
    }.fold(
        onSuccess = { StartResult.Started },
        onFailure = {
            log.w(it) { "$tag: DownloadManager refused the request" }
            StartResult.NotStarted
        },
    )

    override suspend fun cancelAll() {
        val cancelled = synchronized(lock) {
            rows(null) { cursor -> cursor.long(DownloadManager.COLUMN_ID) to cursor.string(DownloadManager.COLUMN_DESCRIPTION) }
                .onEach { (id, _) -> manager.remove(id) }
        }
        // DownloadManager says nothing about a removal, so every cancelled transfer is reported here, as the port promises.
        cancelled.forEach { (_, tag) -> tag?.let { handlers?.onCompleted(it, "cancelled") } }
        log.i { "cancelled ${cancelled.size} transfer(s)" }
    }

    /**
     * One completion broadcast as one wake: [DownloadHandlers.onBackgroundEvents] with [completion], the finished rows
     * delivered (the broadcast's own, and any other a missed broadcast left), then the drain report.
     */
    internal fun deliverBroadcast(id: Long, completion: Completion) {
        val current = handlers ?: return completion.complete()
        current.onBackgroundEvents(completion)
        val delivered = deliverFinished(null)
        log.i { "completion broadcast for $id: delivered $delivered finished transfer(s)" }
        current.onEventsDrained()
    }

    /** Deliver every finished row still held (only [id]'s when given), each removed once delivered. Answers how many. */
    private fun deliverFinished(id: Long?): Int = synchronized(lock) {
        val current = handlers ?: return 0
        val finished = rows(id) { it.finishedRow() }.filterNotNull()
        for (row in finished) {
            runCatchingCancellable { row.deliverTo(current) }
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
            handlers.onFinished(tag, TransferOutcome(HTTP_OK, total, received), path)
            handlers.onCompleted(tag, null)
        } else {
            handlers.onCompleted(tag, "download failed (reason $reason)")
        }
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
    )

    internal companion object {
        /** The subdirectory of the app's external files dir the transfers land in. */
        const val DIRECTORY = "downloads"
        const val HTTP_OK = 200

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
