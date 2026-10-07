package app.snapsync.android.download

import android.app.DownloadManager
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import app.snapsync.android.storage.context
import app.snapsync.contracts.ClauseDownloadHandlers
import app.snapsync.contracts.DownloadEvent
import app.snapsync.model.TransferNetwork
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Android fact behind [AndroidDownload]'s bytes-received rule: when DownloadManager reschedules a RUNNING download
 * — its `BOOT_COMPLETED` handler does so for every row, and so does an app's `control` write, used here — JobScheduler
 * stops the old job but its thread runs on beside a fresh one. Neither has a file yet, so each claims one (`X`, then
 * `X-1`), and the row is opened by whatever path it names at that instant: it can end SUCCESSFUL naming the empty file
 * while the body sits in the other. Measured on API 36: about one run in three.
 *
 * Each thread also preallocates the whole body before it notices it was stopped, so the file the row names can be of
 * full length with its tail never written (CI run 36988179834: 256 of 256 bytes, the content wrong). The port fetches
 * a row the provider ran twice again, under the same tag; this test answers that request too, and judges what it
 * delivers.
 *
 * Which thread wins is the download provider's scheduling, so a run may not meet the split; every finish is held to the
 * invariant all the same — the port reports the bytes the file holds, and never a short or unwritten file as whole. The
 * responses are held until both threads have asked, which is what opens the window.
 */
class AndroidDownloadRescheduleTest {

    private val body = ByteArray(LENGTH) { (it % PRIME).toByte() }
    private val server = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
    private val arrivals = LinkedBlockingQueue<Socket>()

    @BeforeTest
    fun serve() {
        awaitBroadcastsIdle()
        removeAllDownloads()
        thread(isDaemon = true) {
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.readRequestHead()
                arrivals.add(socket)
            }
        }
    }

    @AfterTest
    fun stop() {
        server.close()
        removeAllDownloads()
    }

    @Test
    fun `a download rescheduled while running is never reported whole from a short file`() {
        val outcomes = (0 until RUNS).mapNotNull { run -> raceOnce(run) }
        assertTrue(outcomes.isNotEmpty(), "no run finished at all")
        outcomes.filterIsInstance<DownloadEvent.Finished>().forEach { finished ->
            val read = assertNotNull(finished.body, "${finished.tag}: the file is readable")
            assertEquals(
                read.size.toLong(),
                finished.facts.receivedBytes,
                "${finished.tag}: the bytes received are the file's",
            )
            assertTrue(
                read.contentEquals(body) || finished.facts.receivedBytes < finished.facts.expectedBytes,
                "${finished.tag}: a short file is reported short, so the owner refuses it: ${finished.facts}",
            )
        }
    }

    /**
     * One download rescheduled mid-flight: its finish as the next start delivers it, its failure if the port refused it
     * as raced, or null if it did not stay finished.
     */
    private fun raceOnce(run: Int): DownloadEvent? {
        removeAllDownloads()
        val download = AndroidDownload(context)
        download.listen(ClauseDownloadHandlers { null }.handlers)
        download.start("http://127.0.0.1:${server.localPort}/reschedule-$run", "d-reschedule-$run", TransferNetwork.ANY)
        val first = checkNotNull(
            arrivals.poll(ARRIVAL_SECONDS, TimeUnit.SECONDS),
        ) { "run $run: the download never asked" }
        reschedule(onlyRowId())
        val second = arrivals.poll(ARRIVAL_SECONDS, TimeUnit.SECONDS)
        // The successor answered first: its file is claimed second-to-last, which is what lets the two cross.
        second?.answer()
        first.answer()

        settle()
        // The stopped thread wrote last: the row waits to retry, which a later reconcile would finish. Nothing to judge.
        if (status() != DownloadManager.STATUS_SUCCESSFUL) return null

        var row = onlyRowId()
        var delivered = relaunch()
        // A raced row is fetched again under the same tag, into a fresh row: answer it, and the next start delivers it.
        repeat(AndroidDownload.MAX_RESTARTS) {
            val restarted = rowIdOrNull()
            if (delivered.events.isEmpty() && restarted != null && restarted != row) {
                row = restarted
                settle()
                if (status() == DownloadManager.STATUS_SUCCESSFUL) delivered = relaunch()
            }
        }
        val finished = delivered.events.filterIsInstance<DownloadEvent.Finished>().singleOrNull()
        val refused = delivered.events.firstOrNull { it is DownloadEvent.Completed && it.error != null }
        // SUCCESSFUL is not final either: the stopped thread can still write the row back to retry after it was read
        // (measured on CI: 200 ms later), and an unfinished row is rightly not delivered. Nothing to judge then — but a
        // row that IS finished must have been delivered, or refused past its restarts.
        if (finished == null && refused == null) {
            check(status() != DownloadManager.STATUS_SUCCESSFUL) { "run $run: a finished row was not delivered" }
        }
        return finished ?: refused
    }

    /** Answer every request until the row reads SUCCESSFUL, or [SETTLE_MILLIS] pass. */
    private fun settle() {
        val deadline = System.currentTimeMillis() + SETTLE_MILLIS
        while (status() != DownloadManager.STATUS_SUCCESSFUL && System.currentTimeMillis() < deadline) {
            arrivals.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)?.answer()
        }
    }

    /** A fresh process's start: its pass delivers every finished row. */
    private fun relaunch(): ClauseDownloadHandlers =
        ClauseDownloadHandlers { runCatching { File(it).readBytes() }.getOrNull() }
            .also { AndroidDownload(context).listen(it.handlers) }

    /** What DownloadManager's own (hidden) resume writes: a `control` change reschedules the row's job. */
    private fun reschedule(id: Long) {
        val row = ContentUris.withAppendedId(Uri.parse("content://downloads/my_downloads"), id)
        context.contentResolver.update(row, ContentValues().apply { put("control", 0) }, null, null)
    }

    private fun onlyRowId(): Long = checkNotNull(rowIdOrNull()) { "no download row" }

    private fun rowIdOrNull(): Long? = downloadManager.query(DownloadManager.Query()).use {
        if (it.moveToFirst()) it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)) else null
    }

    private fun status(): Int = downloadManager.query(DownloadManager.Query()).use {
        if (it.moveToFirst()) it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else -1
    }

    private fun Socket.answer() = runCatching {
        getOutputStream().apply {
            write(
                "HTTP/1.0 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray(),
            )
            write(body)
            flush()
        }
        close()
    }

    private fun Socket.readRequestHead() {
        val input = getInputStream()
        var last = 0
        while (last != END_OF_HEAD) {
            val byte = input.read()
            if (byte < 0) return
            last = (last shl Byte.SIZE_BITS) or byte
        }
    }

    private companion object {
        const val LENGTH = 256
        const val PRIME = 251
        const val BACKLOG = 16
        const val RUNS = 12
        const val ARRIVAL_SECONDS = 20L
        const val SETTLE_MILLIS = 3_000L
        const val POLL_MILLIS = 100L

        /** `\r\n\r\n`, the end of a request head. */
        const val END_OF_HEAD = 0x0D0A0D0A
    }
}
