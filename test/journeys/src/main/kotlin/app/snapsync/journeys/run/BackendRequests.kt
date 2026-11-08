package app.snapsync.journeys.run

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The requests the local backend answered, as it tells them: its request log, one line per request, written once the
 * response has finished (`api/src/request-log.ts`) — into [log], the file [LocalBackend] sends its output to.
 *
 * The event a journey waits on where the app cannot tell one: with the app closed, Android freezes its backgrounded
 * process, and the control channel answers nothing (measured 2026-11-07, API 36), so what the app did in the background
 * is told by the system it did it to. The file is followed as it grows — the file system tells each write (inotify,
 * through [java.nio.file.WatchService]) — never re-read on a timer.
 */
class BackendRequests(private val log: File) {

    /** One answered request: `<ISO> [<reqid>] <METHOD> <url> <status> <ms>ms …`. */
    data class Request(val method: String, val url: String, val status: Int) {
        companion object {
            private val LINE = Regex("""^\S+ \[\S+] ([A-Z]+) (\S+) (\d{3}) """)

            fun parse(line: String): Request? =
                LINE.find(line)?.destructured?.let { (method, url, status) -> Request(method, url, status.toInt()) }
        }
    }

    /** Where the requests told from now on start: a wait from it finds nothing answered before. */
    fun mark(): Long = log.length()

    /**
     * Waits for the first request told after [from] that [matches], and answers it — or fails once [within] passed,
     * naming [what] it waited for and the last requests it saw.
     */
    fun await(within: Duration, what: String, from: Long, matches: (Request) -> Boolean): Request {
        val giveUp = TimeSource.Monotonic.markNow() + within
        val seen = ArrayDeque<String>()
        FileSystems.getDefault().newWatchService().use { watch ->
            // Watched before the first read, so a line written between the two is told, not lost.
            log.absoluteFile.parentFile.toPath().register(watch, ENTRY_CREATE, ENTRY_MODIFY)
            var offset = from
            var partial = ByteArray(0)
            while (true) {
                val (lines, next, rest) = readFrom(offset, partial)
                offset = next
                partial = rest
                for (line in lines) {
                    seen.addLast(line)
                    if (seen.size > SHOWN) seen.removeFirst()
                    Request.parse(line)?.takeIf(matches)?.let { return it }
                }
                val left = -giveUp.elapsedNow()
                if (!left.isPositive()) {
                    throw AssertionError(
                        "$what: not within $within. The backend's last requests: " +
                            seen.joinToString("\n", prefix = "\n").ifEmpty { "none" },
                    )
                }
                watch.poll(left.inWholeMilliseconds, TimeUnit.MILLISECONDS)?.run {
                    pollEvents()
                    reset()
                }
            }
        }
    }

    /** The whole lines written since [offset] (after [partial], the unfinished line read last), where they end, and what follows. */
    private fun readFrom(offset: Long, partial: ByteArray): Triple<List<String>, Long, ByteArray> {
        if (!log.exists()) return Triple(emptyList(), offset, partial)
        val bytes = RandomAccessFile(log, "r").use { file ->
            val length = file.length()
            if (length <= offset) return Triple(emptyList(), offset, partial)
            ByteArray((length - offset).toInt()).also {
                file.seek(offset)
                file.readFully(it)
            }
        }
        val all = partial + bytes
        val end = all.lastIndexOf('\n'.code.toByte())
        if (end < 0) return Triple(emptyList(), offset + bytes.size, all)
        val lines = String(all, 0, end, Charsets.UTF_8).lines()
        return Triple(lines, offset + bytes.size, all.copyOfRange(end + 1, all.size))
    }

    private companion object {
        /** How many of the last requests a failure names. */
        const val SHOWN = 8
    }
}
