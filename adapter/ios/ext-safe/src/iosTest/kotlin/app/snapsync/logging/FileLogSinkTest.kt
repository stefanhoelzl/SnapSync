package app.snapsync.logging

import app.snapsync.model.logLineBody
import app.snapsync.model.utcLogStamp
import app.snapsync.testsupport.fileExists
import app.snapsync.testsupport.removeDirectory
import app.snapsync.testsupport.readTextFile
import app.snapsync.testsupport.withTempDirectory
import app.snapsync.testsupport.writeTextFile

import co.touchlab.kermit.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The device log's write side (capability `privacy-security`).
 *
 * This file is the **canonical un-redacted diagnostic channel** — os_log replaces arguments with
 * `<private>`, so when something goes wrong on a real device this is what a person actually reads.
 * Everything asserted below fails silently if it breaks: a log that stopped rolling grows until the
 * container is full, a log that rolled too eagerly discards the evidence, and a stamp without
 * milliseconds cannot order two lines written in the same second — which is precisely what separates
 * "the platform call was slow" from "the process was frozen after it returned" (the deduction
 * SNAPSYNC-6 had to make from durations because the stamps could not say).
 *
 * None of it is reachable from the JVM: `NSFileManager` and the `O_APPEND` write are Foundation and POSIX,
 * and so is the one identity check below — that the arithmetic stamp is the text `NSDate.description` used
 * to produce.
 */
class FileLogSinkTest {

    private fun log(path: String?, maxBytes: Long = 10L * 1024 * 1024) = FileLogSink(path, maxBytes)

    @Test
    fun `a line carries its severity and tag and message`() {
        withTempDirectory { dir ->
            val path = "$dir/debug.log"
            log(path).log(Severity.Info, "enumerated 3 resources", "gallery", null)

            val text = readTextFile(path).orEmpty()
            assertTrue("[Info/gallery] enumerated 3 resources" in text, "unexpected line: $text")
        }
    }

    @Test
    fun `every line ends with a newline so two appends never run together`() {
        withTempDirectory { dir ->
            val path = "$dir/debug.log"
            val writer = log(path)
            writer.log(Severity.Info, "first", "tag", null)
            writer.log(Severity.Info, "second", "tag", null)

            val lines = readTextFile(path).orEmpty().trimEnd('\n').split('\n')
            assertEquals(2, lines.size, "two writes must be two lines")
            assertTrue(lines[0].endsWith("first"))
            assertTrue(lines[1].endsWith("second"))
        }
    }

    /**
     * Millisecond resolution, in the exact shape the stamp promises. The whole reason the clock is read
     * once is that reading it twice could straddle a boundary and stamp a line a full second wrong.
     */
    @Test
    fun `the stamp carries milliseconds ahead of the zone`() {
        withTempDirectory { dir ->
            val path = "$dir/debug.log"
            log(path).log(Severity.Info, "x", "t", null)

            val line = readTextFile(path).orEmpty()
            val stamp = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \+\d{4} """)
            assertTrue(stamp.containsMatchIn(line), "the stamp lost its shape or its milliseconds: $line")
        }
    }

    /** Zero-padding, so the stamps sort lexicographically — the property a reader relies on. */
    @Test
    fun `the millisecond field is always three digits`() {
        withTempDirectory { dir ->
            val path = "$dir/debug.log"
            val writer = log(path)
            // Many writes: a run that never observed a sub-100ms millisecond would prove nothing, so
            // this leans on volume rather than on controlling the clock, which the writer does not
            // take as a parameter.
            repeat(200) { writer.log(Severity.Info, "x", "t", null) }

            readTextFile(path).orEmpty().trimEnd('\n').split('\n').forEach { line ->
                val millis = line.substringAfter('.').substringBefore(' ')
                assertEquals(3, millis.length, "an unpadded millisecond breaks stamp ordering: $line")
            }
        }
    }

    @Test
    fun `the ambient entry point prefixes the line`() {
        withTempDirectory { dir ->
            val path = "$dir/debug.log"
            val owned = LogContext.enter("onSilentPush")
            try {
                log(path).log(Severity.Warn, "reconcile failed", "download", null)
            } finally {
                LogContext.exit(owned)
            }

            val text = readTextFile(path).orEmpty()
            assertTrue(
                "[onSilentPush] [Warn/download] reconcile failed" in text,
                "the prefix is what traces a line back to what triggered it: $text",
            )
        }
    }

    @Test
    fun `a throwable rides on the line it belongs to`() {
        withTempDirectory { dir ->
            val path = "$dir/debug.log"
            log(path).log(Severity.Error, "upload failed", "engine", IllegalStateException("boom"))

            val text = readTextFile(path).orEmpty()
            assertTrue("upload failed | " in text, "the throwable must follow its own message: $text")
            assertTrue("boom" in text, "the throwable's own message must survive: $text")
        }
    }

    // ---- the roll ------------------------------------------------------------------------------

    /**
     * The bound. Without it the log grows without limit inside a container shared with the ledger and
     * every staged download, and the first thing that fails is an unrelated write.
     */
    @Test
    fun `the log rolls to its 1 sibling once it passes the ceiling`() {
        withTempDirectory { dir ->
            val path = "$dir/debug.log"
            val writer = log(path, maxBytes = 64)

            writer.log(Severity.Info, "one", "t", null)
            writer.log(Severity.Info, "two", "t", null) // now past 64 bytes
            writer.log(Severity.Info, "three", "t", null) // this one rolls first

            val rolled = readTextFile("$path.1").orEmpty()
            val current = readTextFile(path).orEmpty()
            assertTrue("one" in rolled && "two" in rolled, "the rolled sibling keeps the history: $rolled")
            assertTrue("three" in current, "the fresh log carries the line that triggered the roll")
            assertFalse("one" in current, "the rolled content must LEAVE the live file: $current")
        }
    }

    /**
     * Two generations, never three. A roll that failed to replace the previous sibling would keep
     * every generation and defeat the bound the roll exists to enforce.
     */
    @Test
    fun `a second roll replaces the previous sibling rather than accumulating`() {
        withTempDirectory { dir ->
            val path = "$dir/debug.log"
            val writer = log(path, maxBytes = 64)

            listOf("one", "two", "three", "four", "five").forEach {
                writer.log(Severity.Info, it, "t", null)
            }

            val rolled = readTextFile("$path.1").orEmpty()
            assertFalse("one" in rolled, "the first generation must be gone, not archived: $rolled")
            assertFalse(fileExists("$path.2"), "there is no second generation — the bound is two files")
        }
    }

    @Test
    fun `a log still under the ceiling does not roll`() {
        withTempDirectory { dir ->
            val path = "$dir/debug.log"
            val writer = log(path, maxBytes = 10L * 1024 * 1024)
            writer.log(Severity.Info, "one", "t", null)
            writer.log(Severity.Info, "two", "t", null)

            assertFalse(fileExists("$path.1"), "rolling early throws away the evidence a dump is for")
            val text = readTextFile(path).orEmpty()
            assertTrue("one" in text && "two" in text)
        }
    }

    /**
     * The stamp's identity with Foundation — the one check [utcLogStamp]'s platform-free test cannot make.
     *
     * The writer used to build the stamp from `NSDate.description` (`yyyy-MM-dd HH:mm:ss +0000`, UTC) with
     * the milliseconds spliced in ahead of the zone. It is now arithmetic, which is cheaper per line and
     * must print byte-identical text; this pins that against the Foundation on the test host, across the
     * instants calendar arithmetic gets wrong.
     */
    @Test
    fun `the arithmetic stamp is the text NSDate's description spliced with milliseconds`() {
        val instants = listOf(
            0L, 7L, 946_684_799_999L, 951_825_600_050L, 951_868_800_000L, 1_677_628_799_999L,
            1_709_251_199_999L, 1_709_251_200_000L, 1_735_689_599_999L, 1_790_172_309_123L, 4_107_542_400_000L,
        )
        for (millis in instants) {
            val desc = NSDate.dateWithTimeIntervalSince1970((millis / 1000).toDouble()).description.orEmpty()
            val zone = desc.lastIndexOf(' ')
            val ms = (millis % 1000).toString().padStart(3, '0')
            val expected = desc.substring(0, zone) + "." + ms + desc.substring(zone)
            assertEquals(expected, utcLogStamp(millis), "epochMillis=$millis")
        }
    }

    // ---- the held descriptor ---------------------------------------------------------------------

    /**
     * The size is tracked in memory from the file's size at open — so a log a previous process left past the
     * ceiling must roll on this process's FIRST line, exactly as the per-line `stat` rolled it.
     */
    @Test
    fun `a log left past the ceiling by a previous process rolls on the first line`() {
        withTempDirectory { dir ->
            val path = "$dir/debug.log"
            writeTextFile(path, "x".repeat(100) + "\n")

            log(path, maxBytes = 64).log(Severity.Info, "fresh", "t", null)

            assertTrue("x".repeat(100) in readTextFile("$path.1").orEmpty(), "the old content is the rolled sibling")
            val current = readTextFile(path).orEmpty()
            assertTrue("fresh" in current && "xxx" !in current, "the first line starts a fresh log: $current")
        }
    }

    /** A log that is appended to keeps what it held: opening once must not truncate. */
    @Test
    fun `an existing log is appended to rather than replaced`() {
        withTempDirectory { dir ->
            val path = "$dir/debug.log"
            writeTextFile(path, "earlier run\n")

            log(path).log(Severity.Info, "this run", "t", null)

            val text = readTextFile(path).orEmpty()
            assertTrue(text.startsWith("earlier run\n") && "this run" in text, "unexpected log: $text")
        }
    }

    /**
     * The descriptor is held open, so a log removed from outside would swallow every later line into an
     * unlinked file. The periodic re-check notices and recreates it.
     */
    @Test
    fun `a log removed from outside is recreated once the re-check runs`() {
        withTempDirectory { dir ->
            val path = "$dir/debug.log"
            var now = 1_790_172_309_123L
            val writer = FileLogSink(path, 10L * 1024 * 1024) { now }
            writer.log(Severity.Info, "before", "t", null)
            removeDirectory(path) // removes a plain file just the same

            now += 1_000
            writer.log(Severity.Info, "after", "t", null)

            val text = readTextFile(path).orEmpty()
            assertTrue("after" in text, "the line after the removal must reach a file someone can read: $text")
            assertFalse("before" in text, "the recreated log is a fresh file")
        }
    }

    /** Lines from many threads arrive whole — the held descriptor and the lock must not cost atomicity. */
    @Test
    fun `lines logged from many threads are never torn`() {
        withTempDirectory { dir ->
            val path = "$dir/debug.log"
            val writer = log(path)
            runBlocking {
                (0 until 4).map { n ->
                    launch(Dispatchers.Default) {
                        repeat(100) { writer.log(Severity.Info, "worker-$n line-$it end", "t", null) }
                    }
                }.joinAll()
            }

            val lines = readTextFile(path).orEmpty().trimEnd('\n').split('\n')
            assertEquals(400, lines.size, "every line lands exactly once")
            lines.forEach { assertTrue(Regex(""" worker-\d line-\d+ end$""").containsMatchIn(it), "a torn line: $it") }
        }
    }

    // ---- degenerate destinations ---------------------------------------------------------------

    /**
     * A writer with no resolvable destination must be inert, not fatal. `LogDestination.path` is
     * nullable precisely because a process may resolve nowhere writable, and the composition roots
     * install this writer before anything else — a throw here would abort the launch it was installed
     * to explain.
     */
    @Test
    fun `a writer with no path writes nothing and raises nothing`() {
        log(path = null).log(Severity.Error, "message", "tag", IllegalStateException("boom"))
    }

    @Test
    fun `a log file is created on first write rather than assumed to exist`() {
        withTempDirectory { dir ->
            val path = "$dir/nested-name.log"
            assertNull(readTextFile(path))

            log(path).log(Severity.Info, "first line", "t", null)

            assertTrue(fileExists(path), "the first line of a fresh install must not be lost")
        }
    }
}

/** One line as the process's log writer hands it to the sink: formatted by `model/logLineBody`, no entry claimed. */
internal fun FileLogSink.log(severity: Severity, message: String, tag: String, throwable: Throwable?) =
    write(severity, tag, logLineBody(LogContext.current, severity.name, tag, message, throwable))
