package app.snapsync.journeys.device

import java.io.File
import java.nio.ByteBuffer
import java.time.LocalDateTime
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class DatedJpegTest {

    private val taken = LocalDateTime.of(2026, 10, 25, 12, 0)

    @Test
    fun the_photo_is_a_camera_sized_jpeg() {
        val file = File(createTempDirectory().toFile(), "taken.jpg").also { DatedJpeg.write(it, taken) }
        val image = checkNotNull(ImageIO.read(file)) { "the file reads as no image" }
        assertEquals(DatedJpeg.WIDTH to DatedJpeg.HEIGHT, image.width to image.height)
    }

    @Test
    fun every_date_a_reader_takes_the_capture_from_is_the_time_taken() {
        val tags = readExif(DatedJpeg.exifSegment(taken))
        assertEquals("2026:10:25 12:00:00", tags[DatedJpeg.DATE_TIME])
        assertEquals("2026:10:25 12:00:00", tags[DatedJpeg.DATE_TIME_ORIGINAL])
        assertEquals("+00:00", tags[DatedJpeg.OFFSET_TIME_ORIGINAL])
    }

    /** Every ASCII tag of IFD0 and the Exif IFD, read as a reader walks them — from the segment's own offsets. */
    private fun readExif(segment: ByteArray): Map<Int, String> {
        assertEquals(listOf(0xFF, 0xE1), listOf(segment[0].toInt() and 0xFF, segment[1].toInt() and 0xFF))
        val length = ByteBuffer.wrap(segment, 2, 2).short.toInt() and 0xFFFF
        assertEquals(segment.size - 2, length, "the segment's length counts itself and its body")
        assertEquals("Exif", String(segment, 4, 4))
        val tiff = ByteBuffer.wrap(segment.copyOfRange(10, segment.size))
        assertEquals("MM", String(byteArrayOf(tiff.get(0), tiff.get(1))))
        assertEquals(42, tiff.getShort(2).toInt())
        val tags = mutableMapOf<Int, String>()
        fun ifd(at: Int) {
            repeat(tiff.getShort(at).toInt()) { i ->
                val entry = at + 2 + i * 12
                val tag = tiff.getShort(entry).toInt() and 0xFFFF
                val count = tiff.getInt(entry + 4)
                val value = tiff.getInt(entry + 8)
                when (tiff.getShort(entry + 2).toInt()) {
                    2 -> tags[tag] = String(tiff.array(), value, count - 1, Charsets.US_ASCII)
                    4 -> if (tag == DatedJpeg.EXIF_IFD) ifd(value)
                }
            }
        }
        ifd(tiff.getInt(4))
        return tags
    }
}
