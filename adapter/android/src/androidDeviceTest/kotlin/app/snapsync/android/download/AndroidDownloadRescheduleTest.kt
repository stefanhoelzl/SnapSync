package app.snapsync.android.download

import android.app.DownloadManager
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import app.snapsync.android.storage.context
import app.snapsync.contracts.ClauseDownloadHandlers
import app.snapsync.contracts.DownloadEvent
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
 * Which thread wins is the download provider's scheduling, so a run may not meet the split; every finish is held to the
 * invariant all the same — the port reports the bytes the file holds, and never a short file as whole. The responses
 * are held until both threads have asked, which is what opens the window.
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
        val delivered = (0 until RUNS).mapNotNull { run -> raceOnce(run) }
        assertTrue(delivered.isNotEmpty(), "no run finished at all")
        delivered.forEach { finished ->
            val read = assertNotNull(finished.body, "${finished.tag}: the file is readable")
            assertEquals(read.size.toLong(), finished.facts.receivedBytes, "${finished.tag}: the bytes received are the file's")
            assertTrue(
                read.contentEquals(body) || finished.facts.receivedBytes < finished.facts.expectedBytes,
                "${finished.tag}: a short file is reported short, so the owner refuses it: ${finished.facts}",
            )
        }
    }

    /** One download rescheduled mid-flight; its finish as the next start delivers it, or null if it did not stay finished. */
    private fun raceOnce(run: Int): DownloadEvent.Finished? {
        removeAllDownloads()
        val download = AndroidDownload(context)
        download.listen(ClauseDownloadHandlers { null }.handlers)
        download.start("http://127.0.0.1:${server.localPort}/reschedule-$run", "d-reschedule-$run")
        val first = checkNotNull(arrivals.poll(ARRIVAL_SECONDS, TimeUnit.SECONDS)) { "run $run: the download never asked" }
        reschedule(onlyRowId())
        val second = arrivals.poll(ARRIVAL_SECONDS, TimeUnit.SECONDS)
        // The successor answered first: its file is claimed second-to-last, which is what lets the two cross.
        second?.answer()
        first.answer()

        val deadline = System.currentTimeMillis() + SETTLE_MILLIS
        while (status() != DownloadManager.STATUS_SUCCESSFUL && System.currentTimeMillis() < deadline) {
            arrivals.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)?.answer()
        }
        // The stopped thread wrote last: the row waits to retry, which a later reconcile would finish. Nothing to judge.
        if (status() != DownloadManager.STATUS_SUCCESSFUL) return null

        val relaunched = ClauseDownloadHandlers { runCatching { File(it).readBytes() }.getOrNull() }
        AndroidDownload(context).listen(relaunched.handlers)
        val finished = relaunched.events.filterIsInstance<DownloadEvent.Finished>().singleOrNull()
        // SUCCESSFUL is not final either: the stopped thread can still write the row back to retry after it was read
        // (measured on CI: 200 ms later), and an unfinished row is rightly not delivered. Nothing to judge then — but a
        // row that IS finished must have been delivered.
        if (finished == null) {
            check(status() != DownloadManager.STATUS_SUCCESSFUL) { "run $run: a finished row was not delivered" }
        }
        return finished
    }

    /** What DownloadManager's own (hidden) resume writes: a `control` change reschedules the row's job. */
    private fun reschedule(id: Long) {
        val row = ContentUris.withAppendedId(Uri.parse("content://downloads/my_downloads"), id)
        context.contentResolver.update(row, ContentValues().apply { put("control", 0) }, null, null)
    }

    private fun onlyRowId(): Long = downloadManager.query(DownloadManager.Query()).use {
        check(it.moveToFirst()) { "no download row" }
        it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
    }

    private fun status(): Int = downloadManager.query(DownloadManager.Query()).use {
        if (it.moveToFirst()) it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else -1
    }

    private fun Socket.answer() = runCatching {
        getOutputStream().apply {
            write("HTTP/1.0 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
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
