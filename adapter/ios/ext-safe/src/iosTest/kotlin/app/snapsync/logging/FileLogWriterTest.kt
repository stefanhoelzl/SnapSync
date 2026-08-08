package app.snapsync.logging

import app.snapsync.testsupport.fileExists
import app.snapsync.testsupport.readTextFile
import app.snapsync.testsupport.withTempDirectory

import co.touchlab.kermit.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The device log's write side (capability `diagnostic-logging`).
 *
 * This file is the **canonical un-redacted diagnostic channel** — os_log replaces arguments with
 * `<private>`, so when something goes wrong on a real device this is what a person actually reads.
 * Everything asserted below fails silently if it breaks: a log that stopped rolling grows until the
 * container is full, a log that rolled too eagerly discards the evidence, and a stamp without
 * milliseconds cannot order two lines written in the same second — which is precisely what separates
 * "the platform call was slow" from "the process was frozen after it returned" (the deduction
 * SNAPSYNC-6 had to make from durations because the stamps could not say).
 *
 * None of it is reachable from the JVM: `NSFileManager`, the `O_APPEND` write and `NSDate`'s
 * description are all Foundation.
 */
class FileLogWriterTest {

    private fun log(path: String?, maxBytes: Long = 10L * 1024 * 1024) = FileLogWriter(path, maxBytes)

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
     * Millisecond resolution, in the exact shape the stamp promises. `NSDate.description` is
     * seconds-only; the milliseconds are spliced in ahead of the zone, and the whole reason the clock
     * is read once and floored is that reading it twice could straddle a boundary and stamp a line a
     * full second wrong.
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
