package app.snapsync.journeys.device

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/**
 * A camera-sized JPEG taken at [taken] (UTC), as a phone's camera would save it: above the policy's 3 MP floor
 * (`SelectionCalibration.imageFloor` — a smaller image reads as received media), and dated in its EXIF, which is
 * where MediaStore's scan takes `datetaken` from — `DateTimeOriginal` and its offset, not only `DateTime` (measured
 * 2026-10-25: a file whose `DateTime` alone was moved kept its `DateTimeOriginal`'s date). Written for a journey that
 * puts a photo in the library while the app is not running, so no app may write it.
 */
object DatedJpeg {
    const val WIDTH = 2400
    const val HEIGHT = 1600

    fun write(into: File, taken: LocalDateTime) {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().run {
                color = java.awt.Color(SKY_RED, SKY_GREEN, SKY_BLUE)
                fillRect(0, 0, WIDTH, HEIGHT)
                dispose()
            }
        }
        val jpeg = ByteArrayOutputStream().also {
            check(
                ImageIO.write(image, "jpg", it),
            ) { "no JPEG writer" }
        }.toByteArray()
        check(jpeg[0] == MARKER && jpeg[1] == SOI) { "ImageIO wrote no JPEG" }
        // The Exif segment goes right after the start of image, where a reader looks for it first.
        into.writeBytes(jpeg.copyOfRange(0, 2) + exifSegment(taken) + jpeg.copyOfRange(2, jpeg.size))
    }

    /** `APP1`: `Exif\0\0`, then a big-endian TIFF — IFD0 (`DateTime`, the Exif IFD's offset) and the Exif IFD. */
    internal fun exifSegment(taken: LocalDateTime): ByteArray {
        val date = ascii(taken.format(EXIF_DATE))
        val offset = ascii(UTC_OFFSET)
        // Offsets are from the TIFF header's start: its 8 bytes, IFD0 (2 entries), its date, the Exif IFD (2 entries),
        // their date and offset.
        val ifd0 = TIFF_HEADER
        val ifd0Date = ifd0 + ifdSize(2)
        val exifIfd = ifd0Date + date.size
        val exifDate = exifIfd + ifdSize(2)
        val exifOffset = exifDate + date.size
        val tiff = ByteBuffer.allocate(exifOffset + offset.size)
            .put(byteArrayOf('M'.code.toByte(), 'M'.code.toByte())).putShort(TIFF_MAGIC).putInt(ifd0)
            .putShort(2).entry(DATE_TIME, ASCII, date.size, ifd0Date).entry(EXIF_IFD, LONG, 1, exifIfd).putInt(0)
            .put(date)
            .putShort(2).entry(DATE_TIME_ORIGINAL, ASCII, date.size, exifDate)
            .entry(OFFSET_TIME_ORIGINAL, ASCII, offset.size, exifOffset).putInt(0)
            .put(date).put(offset)
            .array()
        val body = EXIF_HEADER + tiff
        return ByteBuffer.allocate(
            4 + body.size,
        ).put(MARKER).put(APP1).putShort((2 + body.size).toShort()).put(body).array()
    }

    private fun ByteBuffer.entry(tag: Int, type: Short, count: Int, value: Int): ByteBuffer =
        putShort(tag.toShort()).putShort(type).putInt(count).putInt(value)

    private fun ifdSize(entries: Int) = 2 + entries * IFD_ENTRY + 4

    /** NUL-terminated, as an EXIF ASCII value is. */
    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII) + 0

    private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private const val UTC_OFFSET = "+00:00"
    private val EXIF_HEADER = byteArrayOf(
        'E'.code.toByte(),
        'x'.code.toByte(),
        'i'.code.toByte(),
        'f'.code.toByte(),
        0,
        0,
    )
    private const val MARKER = 0xFF.toByte()
    private const val SOI = 0xD8.toByte()
    private const val APP1 = 0xE1.toByte()
    private const val TIFF_HEADER = 8
    private const val TIFF_MAGIC: Short = 42
    private const val IFD_ENTRY = 12
    private const val ASCII: Short = 2
    private const val LONG: Short = 4
    internal const val DATE_TIME = 0x0132
    internal const val EXIF_IFD = 0x8769
    internal const val DATE_TIME_ORIGINAL = 0x9003
    internal const val OFFSET_TIME_ORIGINAL = 0x9011
    private const val SKY_RED = 40
    private const val SKY_GREEN = 120
    private const val SKY_BLUE = 200
}
