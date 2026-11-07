package app.snapsync.data.logs

import app.snapsync.domain.report.Fact
import app.snapsync.mock.inMemoryFiles
import app.snapsync.ports.FileArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The device log's read side — what a diagnostic dump actually sends (capability `privacy-security`).
 *
 * Two things here are silent when wrong. **Which file is which**: a dump that carried the app's log twice would
 * look completely normal while answering the one question a dump exists to answer ("what did the *other* process
 * see?") with a copy of what the reader already had. And **which end** is read: a regression to reading the head
 * would send a report about a launch days before the problem, and send it successfully.
 */
class DeviceLogTailsTest {

    private val shared = mutableMapOf<String, ByteArray>()
    private val private = mutableMapOf<String, ByteArray>()

    private fun service(denied: Set<Pair<FileArea, String>> = emptySet()) =
        DeviceLogTails(inMemoryFiles(shared = shared, private = private, denied = denied))

    private fun MutableMap<String, ByteArray>.put(path: String, text: String) {
        this[path] = text.encodeToByteArray()
    }

    @Test
    fun `the app tail reads the private debug log and the extension tail the shared ext-debug log`() {
        private.put("debug.log", "\nwritten by the app process\n")
        shared.put("ext-debug.log", "\nwritten by the upload extension\n")

        assertTrue(service().text(LoggingProcess.APP, 4096).contains("by the app process"))
        assertTrue(
            service().text(LoggingProcess.EXTENSION, 4096).contains("by the upload extension"),
            "a swapped pair sends the app's log twice — and looks entirely normal",
        )
    }

    @Test
    fun `a log in the wrong area is not borrowed`() {
        shared.put("debug.log", "\nthe app log misplaced\n")
        private.put("ext-debug.log", "\nthe extension log misplaced\n")

        assertEquals(Fact.Unsupported, service().tail(LoggingProcess.APP, 4096))
        assertEquals(Fact.Unsupported, service().tail(LoggingProcess.EXTENSION, 4096))
    }

    @Test
    fun `a tail reads the END of the file`() {
        private.put("debug.log", (1..500).joinToString("\n") { "line-$it" })

        val text = service().text(LoggingProcess.APP, 200)

        assertTrue("line-500" in text, "the newest lines are the ones worth sending: $text")
        assertFalse("line-1\n" in text, "the head must not be what a bounded tail returns")
    }

    @Test
    fun `a tail never begins mid-line`() {
        private.put("debug.log", (1..500).joinToString("\n") { "2026-08-08 12:00:00.000 +0000 line-$it" })

        val text = service().text(LoggingProcess.APP, 200)

        assertTrue(
            text.startsWith("2026-08-08 "),
            "a dump that opens in the middle of a timestamp reads as corruption: ${text.take(60)}",
        )
    }

    /** A whole file within the budget was read from its first byte, so nothing was cut and `alpha` is whole. */
    @Test
    fun `a log shorter than the budget comes back complete`() {
        private.put("debug.log", "alpha\nbeta\ngamma\n")

        assertEquals("alpha\nbeta\ngamma\n", service().text(LoggingProcess.APP, 4096))
    }

    @Test
    fun `a cut tail with no newline at all is returned rather than discarded`() {
        private.put("debug.log", "x".repeat(100) + "one very long line")

        assertEquals("one very long line", service().text(LoggingProcess.APP, 18))
    }

    @Test
    fun `everything before the first newline of a cut tail is dropped`() {
        private.put("debug.log", "a partial\nsecond\nthird")

        assertEquals("second\nthird", service().text(LoggingProcess.APP, "rtial\nsecond\nthird".length))
    }

    // ---- absence ------------------------------------------------------------------------------

    /**
     * Several different nothings, one answer — and the answer is no tail, never `""`. An empty string would render
     * in a dump as a log that ran and said nothing, the single most misleading thing a diagnostic report can claim.
     */
    @Test
    fun `a missing file reads as no tail rather than as an empty log`() {
        assertEquals(Fact.Unsupported, service().tail(LoggingProcess.APP, 4096))
        assertEquals(Fact.Unsupported, service().tail(LoggingProcess.EXTENSION, 4096))
    }

    /** The rolled `.1` sibling is the log BEFORE the last roll: stale, and a dump that sent it would mislead. */
    @Test
    fun `only a rolled sibling reads as no tail`() {
        private.put("debug.log.1", "\nthe app's previous log\n")
        shared.put("ext-debug.log.1", "\nthe extension's previous log\n")

        assertEquals(Fact.Unsupported, service().tail(LoggingProcess.APP, 4096))
        assertEquals(Fact.Unsupported, service().tail(LoggingProcess.EXTENSION, 4096))
    }

    @Test
    fun `an empty file reads as no tail`() {
        private.put("debug.log", "")

        assertEquals(Fact.Unsupported, service().tail(LoggingProcess.APP, 4096))
    }

    @Test
    fun `a denied log is failed with why - never read as absent`() {
        shared.put("ext-debug.log", "\ncontent\n")

        val read = service(denied = setOf(FileArea.SHARED to "ext-debug.log")).tail(LoggingProcess.EXTENSION, 4096)
        assertTrue(assertIs<Fact.Failed>(read).reason.startsWith("the extension log was denied"), read.reason)
    }

    @Test
    fun `a log that cannot be read is failed with what the platform said`() {
        private.put("debug.log/child", "a directory where the log should be")

        val read = service().tail(LoggingProcess.APP, 4096)
        assertTrue(assertIs<Fact.Failed>(read).reason.startsWith("the app log could not be read"), read.reason)
    }

    @Test
    fun `an unavailable shared area is failed - never the other process's log`() {
        private.put("debug.log", "\ncontent\n")
        val source = DeviceLogTails(inMemoryFiles(shared = null, private = private))

        assertEquals(
            Fact.Failed("the extension log's area is unavailable"),
            source.tail(LoggingProcess.EXTENSION, 4096),
            "a process with no reachable log has no tail — it must not borrow the other's",
        )
    }

    @Test
    fun `a non-positive budget reads as no tail`() {
        private.put("debug.log", "\ncontent\n")

        assertEquals(Fact.Unsupported, service().tail(LoggingProcess.APP, 0))
        assertEquals(Fact.Unsupported, service().tail(LoggingProcess.APP, -1))
    }

    /** The tail's text, where there is one. */
    private fun DeviceLogTails.text(process: LoggingProcess, maxBytes: Int): String =
        assertIs<Fact.Known<String>>(tail(process, maxBytes)).value
}
