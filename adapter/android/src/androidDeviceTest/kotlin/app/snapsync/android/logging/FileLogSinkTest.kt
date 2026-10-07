package app.snapsync.android.logging

import app.snapsync.android.storage.AndroidFiles
import app.snapsync.android.storage.newTempDirectory
import app.snapsync.model.APP_LOG_FILE_NAME
import app.snapsync.services.logs.LogTailService
import co.touchlab.kermit.Severity
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The Android device log's write side (capability `privacy-security`): the file a bug report's `app_log` is read
 * from. Everything here fails silently if it breaks — a log that is never written leaves every Android bug report
 * with an empty `app_log` (SNAPSYNC-43), one that stops rolling grows until storage is full, one that rolls too
 * eagerly discards the evidence.
 */
class FileLogSinkTest {

    private fun logFile(): File = File(newTempDirectory(), APP_LOG_FILE_NAME)

    private fun File.lines(): List<String> = readText().trimEnd('\n').split('\n')

    @Test
    fun `a line is stamped and ends with a newline so two appends never run together`() {
        val file = logFile()
        val sink = FileLogSink(file)
        sink.write(Severity.Info, "t", "[Info/t] first")
        sink.write(Severity.Info, "t", "[Info/t] second")

        val lines = file.lines()
        assertEquals(2, lines.size, "two writes must be two lines")
        val stamp = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \+\d{4} \[Info/t] first$""")
        assertTrue(stamp.matches(lines[0]), "the stamp lost its shape: ${lines[0]}")
        assertTrue(lines[1].endsWith("[Info/t] second"))
    }

    @Test
    fun `a log file and its directory are created on first write`() {
        val file = File(File(newTempDirectory(), "private"), APP_LOG_FILE_NAME)
        FileLogSink(file).write(Severity.Info, "t", "x")
        assertTrue(file.exists())
    }

    @Test
    fun `an existing log is appended to rather than replaced`() {
        val file = logFile()
        file.writeText("from the last process\n")
        FileLogSink(file).write(Severity.Info, "t", "from this one")

        val lines = file.lines()
        assertEquals("from the last process", lines[0])
        assertTrue(lines[1].endsWith("from this one"))
    }

    @Test
    fun `the log rolls to its 1 sibling once it reaches the ceiling and a second roll replaces it`() {
        val file = logFile()
        val sibling = File(file.path + ".1")
        val sink = FileLogSink(file, maxBytes = SMALL_CEILING)
        repeat(LINES_PAST_CEILING) { sink.write(Severity.Info, "t", "first generation $it ".padEnd(LINE_WIDTH, '.')) }
        assertTrue(sibling.exists(), "past the ceiling the log must roll")
        assertFalse(file.readText().contains("first generation 0"), "the live log restarts fresh after a roll")

        repeat(LINES_PAST_CEILING) { sink.write(Severity.Info, "t", "second generation $it ".padEnd(LINE_WIDTH, '.')) }
        assertTrue(sibling.readText().contains("second generation"), "a later roll replaces the sibling")
        assertFalse(sibling.readText().contains("first generation 0"), "siblings never accumulate")
    }

    @Test
    fun `a log still under the ceiling does not roll`() {
        val file = logFile()
        val sink = FileLogSink(file, maxBytes = WHOLE_BUDGET.toLong())
        repeat(FEW_LINES) { sink.write(Severity.Info, "t", "line $it") }
        assertFalse(File(file.path + ".1").exists())
        assertEquals(FEW_LINES, file.lines().size)
    }

    @Test
    fun `a log left past the ceiling by a previous process rolls on the first line`() {
        val file = logFile()
        file.writeText("x".repeat(SMALL_CEILING.toInt() + LINE_WIDTH) + "\n")
        FileLogSink(file, maxBytes = SMALL_CEILING).write(Severity.Info, "t", "fresh")

        assertTrue(File(file.path + ".1").exists())
        assertEquals(1, file.lines().size)
    }

    @Test
    fun `a log removed from outside is recreated once the re-check runs`() {
        val file = logFile()
        var now = Instant.fromEpochMilliseconds(START_MS)
        val clock = object : Clock {
            override fun now() = now
        }
        val sink = FileLogSink(file, FileLogSink.DEFAULT_MAX_BYTES, clock)
        sink.write(Severity.Info, "t", "before")
        assertTrue(file.delete())

        now += (FileLogSink.RECHECK_INTERVAL_MS + 1).milliseconds
        sink.write(Severity.Info, "t", "after")

        assertTrue(file.exists(), "a removed log must be recreated, not written into an unlinked file")
        assertTrue(file.readText().contains("after"))
    }

    @Test
    fun `lines logged from many threads are never torn`() {
        val file = logFile()
        val sink = FileLogSink(file)
        val threads = (0 until THREADS).map { t ->
            thread {
                repeat(
                    LINES_PER_THREAD,
                ) { sink.write(Severity.Info, "t", "thread $t line $it ".padEnd(WIDE_LINE, '#')) }
            }
        }
        threads.forEach { it.join() }

        val lines = file.lines()
        assertEquals(THREADS * LINES_PER_THREAD, lines.size)
        lines.forEach { assertTrue(Regex(""" thread \d line \d+ #+$""").containsMatchIn(it), "torn line: $it") }
    }

    /** The point of the file: what the sink writes is what a bug report's log-tail read finds. */
    @Test
    fun `the app log a dump reads is the one the sink writes`() = runTest {
        val shared = newTempDirectory()
        val private = newTempDirectory()
        val sink = FileLogSink(File(private, APP_LOG_FILE_NAME))
        sink.write(Severity.Warn, "CreateEvent", "[Warn/CreateEvent] create failed")

        val tail = LogTailService(AndroidFiles(sharedRoot = shared, privateRoot = private))
            .tail(LogTailService.Process.APP, WHOLE_BUDGET)
        assertTrue(tail.orEmpty().contains("[Warn/CreateEvent] create failed"), "the dump would miss it: $tail")
        assertEquals(
            null,
            LogTailService(AndroidFiles(shared, private)).tail(LogTailService.Process.EXTENSION, WHOLE_BUDGET),
        )
    }

    private companion object {
        /** A ceiling a handful of stamped lines passes. */
        const val SMALL_CEILING = 200L
        const val LINE_WIDTH = 40

        /** Enough [LINE_WIDTH] lines, stamped, to pass [SMALL_CEILING]. */
        const val LINES_PAST_CEILING = 6
        const val FEW_LINES = 10
        const val WHOLE_BUDGET = 10_000
        const val START_MS = 1_000_000L
        const val THREADS = 8
        const val LINES_PER_THREAD = 200
        const val WIDE_LINE = 120
    }
}
