package app.snapsync.domain.asset

import app.snapsync.domain.time.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/** What the platform says an asset is: its pixel area and its capture date, as the selection reads them. */
class AssetFactsTest {

    @Test
    fun `a missing or non-positive pixel count is unknown, never tiny`() {
        assertEquals(PixelArea.Unknown, PixelArea.of(null))
        assertEquals(PixelArea.Unknown, PixelArea.of(0))
        assertEquals(PixelArea.Unknown, PixelArea.of(-12))
        assertEquals(4_000_000L, assertIs<PixelArea.Known>(PixelArea.of(4_000_000)).pixels)
    }

    @Test
    fun `an unknown area reaches every floor, a known one only from the floor up`() {
        val floor = PixelArea.Known(1_000)
        assertTrue(PixelArea.Unknown.reaches(floor), "admit on doubt")
        assertTrue(PixelArea.Known(1_000).reaches(floor), "at the floor")
        assertTrue(PixelArea.Known(1_001).reaches(floor))
        assertFalse(PixelArea.Known(999).reaches(floor))
    }

    @Test
    fun `a screen recording is a moving screen capture, a screenshot a still one`() {
        assertEquals(
            MediaKind.SCREEN_RECORDING,
            MediaKind.of(isVideo = true, isScreenshot = false, isScreenRecording = true),
        )
        assertEquals(MediaKind.VIDEO, MediaKind.of(isVideo = true, isScreenshot = false, isScreenRecording = false))
        assertEquals(
            MediaKind.SCREENSHOT,
            MediaKind.of(isVideo = false, isScreenshot = true, isScreenRecording = false),
        )
        assertEquals(MediaKind.PHOTO, MediaKind.of(isVideo = false, isScreenshot = false, isScreenRecording = false))
        assertEquals(listOf(MediaKind.VIDEO, MediaKind.SCREEN_RECORDING), MediaKind.entries.filter { it.isMoving })
    }

    @Test
    fun `a capture date is what the platform records, or none`() {
        val at = Instant.parse("2026-06-01T10:00:00Z")
        assertEquals(CaptureDate.Undated, CaptureDate.of(null))
        assertEquals(CaptureDate.Dated(Timestamp.of(at)), CaptureDate.of(at))
    }

    @Test
    fun `a date lies within a window from its start to its end, both included, and an undated one never does`() {
        val from = Timestamp.of(Instant.parse("2026-06-01T00:00:00Z"))
        val until = Timestamp.of(Instant.parse("2026-06-03T00:00:00Z"))
        fun dated(text: String): CaptureDate = CaptureDate.Dated(Timestamp.of(Instant.parse(text)))
        assertTrue(dated("2026-06-01T00:00:00Z").within(from, until))
        assertTrue(dated("2026-06-02T12:00:00Z").within(from, until))
        assertTrue(dated("2026-06-03T00:00:00Z").within(from, until))
        assertFalse(dated("2026-05-31T23:59:59Z").within(from, until))
        assertFalse(dated("2026-06-03T00:00:01Z").within(from, until))
        assertFalse(CaptureDate.Undated.within(from, until))
    }
}
