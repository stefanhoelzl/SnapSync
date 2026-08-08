package app.snapsync.logging

import app.snapsync.ports.DeviceLogSource
import app.snapsync.testsupport.withTempDirectory
import app.snapsync.testsupport.writeTextFile

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The device log's read side — what a diagnostic dump actually sends (capability
 * `diagnostic-logging`).
 *
 * Two things here are silent when wrong, and both are asserted below. **Which file is which**: the
 * app's log and the extension's log are two different processes' accounts of the same minutes, and a
 * dump that carried the app's log twice would look completely normal — same shape, same timestamps,
 * plausible content — while answering the one question a dump exists to answer ("what did the *other*
 * process see?") with a copy of what the reader already had. And **which end** is read: this seeks to
 * `size - maxBytes` because a log is bounded at 10 MB and a dump wants its last few hundred KB; a
 * regression to reading the head would send a report about a launch that happened days before the
 * problem, and it would send it successfully.
 */
class IosDeviceLogSourceTest {

    private fun tail(source: IosDeviceLogSource, process: DeviceLogSource.Process, budget: Int) =
        runBlocking { source.tail(process, budget) }

    @Test
    fun `the app tail comes from the app log and the extension tail from the extension log`() {
        withTempDirectory { dir ->
            val app = "$dir/debug.log"
            val ext = "$dir/ext-debug.log"
            writeTextFile(app, "\nwritten by the app process\n")
            writeTextFile(ext, "\nwritten by the upload extension\n")
            val source = IosDeviceLogSource(appLogPath = app, extensionLogPath = ext)

            assertTrue(
                tail(source, DeviceLogSource.Process.APP, 4096).orEmpty().contains("by the app process"),
            )
            assertTrue(
                tail(source, DeviceLogSource.Process.EXTENSION, 4096).orEmpty()
                    .contains("by the upload extension"),
                "a swapped pair sends the app's log twice — and looks entirely normal",
            )
        }
    }

    @Test
    fun `a tail reads the END of the file`() {
        withTempDirectory { dir ->
            val path = "$dir/debug.log"
            val lines = (1..500).joinToString("\n") { "line-$it" }
            writeTextFile(path, lines)
            val source = IosDeviceLogSource(appLogPath = path, extensionLogPath = null)

            val text = tail(source, DeviceLogSource.Process.APP, 200).orEmpty()

            assertTrue("line-500" in text, "the newest lines are the ones worth sending: $text")
            assertFalse("line-1\n" in text, "the head must not be what a bounded tail returns")
        }
    }

    @Test
    fun `a tail never begins mid-line`() {
        withTempDirectory { dir ->
            val path = "$dir/debug.log"
            writeTextFile(path, (1..500).joinToString("\n") { "2026-08-08 12:00:00.000 +0000 line-$it" })
            val source = IosDeviceLogSource(appLogPath = path, extensionLogPath = null)

            val text = tail(source, DeviceLogSource.Process.APP, 200).orEmpty()

            assertTrue(
                text.startsWith("2026-08-08 "),
                "a dump that opens in the middle of a timestamp reads as corruption: ${text.take(60)}",
            )
        }
    }

    /** A whole file smaller than the budget is returned whole, minus nothing it did not have. */
    @Test
    fun `a log shorter than the budget comes back complete`() {
        withTempDirectory { dir ->
            val path = "$dir/debug.log"
            writeTextFile(path, "alpha\nbeta\ngamma\n")
            val source = IosDeviceLogSource(appLogPath = path, extensionLogPath = null)

            // The partial-first-line rule still applies: the first whole line here is `beta`.
            assertEquals("beta\ngamma\n", tail(source, DeviceLogSource.Process.APP, 4096))
        }
    }

    @Test
    fun `text with no newline at all is returned rather than discarded`() {
        assertEquals("one very long line", fromFirstWholeLine("one very long line"))
    }

    @Test
    fun `everything before the first newline is dropped`() {
        assertEquals("second\nthird", fromFirstWholeLine("partia\nsecond\nthird"))
    }

    // ---- absence ------------------------------------------------------------------------------

    /**
     * Three different nothings, one answer — and the answer is `null`, never `""`. An empty string
     * would render in a dump as a log that ran and said nothing, which is the single most misleading
     * thing a diagnostic report can claim.
     */
    @Test
    fun `a missing file reads as null rather than as an empty log`() {
        withTempDirectory { dir ->
            val source = IosDeviceLogSource("$dir/absent.log", extensionLogPath = null)
            assertNull(tail(source, DeviceLogSource.Process.APP, 4096))
        }
    }

    @Test
    fun `an empty file reads as null`() {
        withTempDirectory { dir ->
            val path = "$dir/debug.log"
            writeTextFile(path, "")
            val source = IosDeviceLogSource(appLogPath = path, extensionLogPath = null)

            assertNull(tail(source, DeviceLogSource.Process.APP, 4096))
        }
    }

    @Test
    fun `an unresolved destination reads as null`() {
        withTempDirectory { dir ->
            writeTextFile("$dir/debug.log", "\ncontent\n")
            val source = IosDeviceLogSource(appLogPath = "$dir/debug.log", extensionLogPath = null)

            assertNull(
                tail(source, DeviceLogSource.Process.EXTENSION, 4096),
                "a process with no log destination has no tail — it must not borrow the other's",
            )
        }
    }

    @Test
    fun `a non-positive budget reads as null`() {
        withTempDirectory { dir ->
            val path = "$dir/debug.log"
            writeTextFile(path, "\ncontent\n")
            val source = IosDeviceLogSource(appLogPath = path, extensionLogPath = null)

            assertNull(tail(source, DeviceLogSource.Process.APP, 0))
            assertNull(tail(source, DeviceLogSource.Process.APP, -1))
        }
    }
}
