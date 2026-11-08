package app.snapsync.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A device-log line's body: its trigger, level and tag, then its text — and a cause after it, on the same line. */
class DeviceLogTextTest {
    @Test
    fun a_line_carries_its_trigger_level_tag_and_message() {
        assertEquals(
            "[onSilentPush] [Warn/download] reconcile failed",
            DeviceLogText.body("onSilentPush", "Warn", "download", "reconcile failed", null),
        )
        assertEquals("[Info/gallery] enumerated 3", DeviceLogText.body(null, "Info", "gallery", "enumerated 3", null))
    }

    @Test
    fun a_throwable_follows_the_message_on_the_same_line() {
        val line = DeviceLogText.body(null, "Error", "engine", "upload failed", IllegalStateException("boom"))
        assertTrue(line.startsWith("[Error/engine] upload failed | "), "unexpected line: $line")
        assertTrue("boom" in line)
    }
}
