package app.snapsync.services.logs

import app.snapsync.fake.inMemoryFiles
import app.snapsync.model.FileArea
import app.snapsync.ports.DeviceLogSource

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The device log's read side — what a diagnostic dump actually sends (capability `privacy-security`).
 *
 * Two things here are silent when wrong. **Which file is which**: a dump that carried the app's log twice would
 * look completely normal while answering the one question a dump exists to answer ("what did the *other* process
 * see?") with a copy of what the reader already had. And **which end** is read: a regression to reading the head
 * would send a report about a launch days before the problem, and send it successfully.
 */
class LogTailServiceTest {

    private val shared = mutableMapOf<String, ByteArray>()
    private val private = mutableMapOf<String, ByteArray>()

    private fun service(denied: Set<Pair<FileArea, String>> = emptySet()) =
        LogTailService(inMemoryFiles(shared = shared, private = private, denied = denied))

    private fun MutableMap<String, ByteArray>.put(path: String, text: String) {
        this[path] = text.encodeToByteArray()
    }

    @Test
    fun `the app tail reads the private debug log and the extension tail the shared ext-debug log`() = runTest {
        private.put("debug.log", "\nwritten by the app process\n")
        shared.put("ext-debug.log", "\nwritten by the upload extension\n")

        assertTrue(service().tail(DeviceLogSource.Process.APP, 4096).orEmpty().contains("by the app process"))
        assertTrue(
            service().tail(DeviceLogSource.Process.EXTENSION, 4096).orEmpty().contains("by the upload extension"),
            "a swapped pair sends the app's log twice — and looks entirely normal",
        )
    }

    @Test
    fun `a log in the wrong area is not borrowed`() = runTest {
        shared.put("debug.log", "\nthe app log misplaced\n")
        private.put("ext-debug.log", "\nthe extension log misplaced\n")

        assertNull(service().tail(DeviceLogSource.Process.APP, 4096))
        assertNull(service().tail(DeviceLogSource.Process.EXTENSION, 4096))
    }

    @Test
    fun `a tail reads the END of the file`() = runTest {
        private.put("debug.log", (1..500).joinToString("\n") { "line-$it" })

        val text = service().tail(DeviceLogSource.Process.APP, 200).orEmpty()

        assertTrue("line-500" in text, "the newest lines are the ones worth sending: $text")
        assertFalse("line-1\n" in text, "the head must not be what a bounded tail returns")
    }

    @Test
    fun `a tail never begins mid-line`() = runTest {
        private.put("debug.log", (1..500).joinToString("\n") { "2026-08-08 12:00:00.000 +0000 line-$it" })

        val text = service().tail(DeviceLogSource.Process.APP, 200).orEmpty()

        assertTrue(
            text.startsWith("2026-08-08 "),
            "a dump that opens in the middle of a timestamp reads as corruption: ${text.take(60)}",
        )
    }

    /** A whole file within the budget was read from its first byte, so nothing was cut and `alpha` is whole. */
    @Test
    fun `a log shorter than the budget comes back complete`() = runTest {
        private.put("debug.log", "alpha\nbeta\ngamma\n")

        assertEquals("alpha\nbeta\ngamma\n", service().tail(DeviceLogSource.Process.APP, 4096))
    }

    @Test
    fun `a cut tail with no newline at all is returned rather than discarded`() = runTest {
        private.put("debug.log", "x".repeat(100) + "one very long line")

        assertEquals("one very long line", service().tail(DeviceLogSource.Process.APP, 18))
    }

    @Test
    fun `everything before the first newline of a cut tail is dropped`() = runTest {
        private.put("debug.log", "a partial\nsecond\nthird")

        assertEquals("second\nthird", service().tail(DeviceLogSource.Process.APP, "rtial\nsecond\nthird".length))
    }

    // ---- absence ------------------------------------------------------------------------------

    /**
     * Several different nothings, one answer — and the answer is `null`, never `""`. An empty string would render
     * in a dump as a log that ran and said nothing, the single most misleading thing a diagnostic report can claim.
     */
    @Test
    fun `a missing file reads as null rather than as an empty log`() = runTest {
        assertNull(service().tail(DeviceLogSource.Process.APP, 4096))
        assertNull(service().tail(DeviceLogSource.Process.EXTENSION, 4096))
    }

    @Test
    fun `an empty file reads as null`() = runTest {
        private.put("debug.log", "")

        assertNull(service().tail(DeviceLogSource.Process.APP, 4096))
    }

    @Test
    fun `a denied log reads as null`() = runTest {
        shared.put("ext-debug.log", "\ncontent\n")

        assertNull(service(denied = setOf(FileArea.SHARED to "ext-debug.log")).tail(DeviceLogSource.Process.EXTENSION, 4096))
    }

    @Test
    fun `an unavailable shared area reads as null`() = runTest {
        private.put("debug.log", "\ncontent\n")
        val source = LogTailService(inMemoryFiles(shared = null, private = private))

        assertNull(
            source.tail(DeviceLogSource.Process.EXTENSION, 4096),
            "a process with no reachable log has no tail — it must not borrow the other's",
        )
    }

    @Test
    fun `a non-positive budget reads as null`() = runTest {
        private.put("debug.log", "\ncontent\n")

        assertNull(service().tail(DeviceLogSource.Process.APP, 0))
        assertNull(service().tail(DeviceLogSource.Process.APP, -1))
    }
}
