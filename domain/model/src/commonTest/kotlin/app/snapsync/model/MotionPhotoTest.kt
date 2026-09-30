package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionPhotoTest {

    @Test
    fun a_jpeg_without_xmp_gets_one_after_its_jfif_and_exif_segments() {
        val jpeg = jpeg(jfif(), exif())
        val still = assertNotNull(motionPhotoStill(jpeg, videoLength = 1234))

        val segments = assertNotNull(JpegSegments.leading(still))
        assertEquals(listOf(0xE0, 0xE1, 0xE1, 0xDB), segments.map { it.marker })
        assertTrue(segments[2].isXmp(still), "the XMP segment follows JFIF and EXIF")
        assertContentEquals(imageData(jpeg), imageData(still), "the image data is untouched")
    }

    @Test
    fun a_bare_jpeg_gets_its_xmp_right_after_the_soi() {
        val still = assertNotNull(motionPhotoStill(jpeg(), videoLength = 10))
        assertTrue(assertNotNull(JpegSegments.leading(still)).first().isXmp(still))
    }

    @Test
    fun the_packet_carries_both_tag_sets() {
        val xmp = assertNotNull(jpegXmp(assertNotNull(motionPhotoStill(jpeg(), videoLength = 4321))))
        for (property in listOf(
            "GCamera:MicroVideo=\"1\"",
            "GCamera:MicroVideoOffset=\"4321\"",
            "Camera:MotionPhoto=\"1\"",
            "Camera:MotionPhotoVersion=\"1\"",
            "Item:Semantic=\"Primary\"",
            "Item:Semantic=\"MotionPhoto\" Item:Length=\"4321\"",
        )) {
            assertTrue(property in xmp, "missing $property")
        }
    }

    @Test
    fun an_existing_xmp_packet_is_merged_into_not_duplicated() {
        val original = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="${MotionPhoto.RDF_NS}">""" +
            """<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:CreatorTool="26.0"/>""" +
            "</rdf:RDF></x:xmpmeta>"
        val jpeg = jpeg(jfif(), exif(), assertNotNull(JpegSegments.xmpSegment(original)))
        val still = assertNotNull(motionPhotoStill(jpeg, videoLength = 99))

        val segments = assertNotNull(JpegSegments.leading(still))
        assertEquals(1, segments.count { it.isXmp(still) }, "still one XMP segment")
        val xmp = assertNotNull(jpegXmp(still))
        assertTrue("xmp:CreatorTool=\"26.0\"" in xmp, "the sender's properties survive")
        assertTrue("Camera:MotionPhoto=\"1\"" in xmp)
        assertContentEquals(imageData(jpeg), imageData(still))
    }

    @Test
    fun a_jpeg_that_already_is_a_motion_photo_is_not_rewritten() {
        val jpeg = jpeg(assertNotNull(JpegSegments.xmpSegment(MotionPhoto.packet(5))))
        assertNull(motionPhotoStill(jpeg, videoLength = 7))
    }

    @Test
    fun an_xmp_packet_without_rdf_cannot_be_merged() {
        val jpeg = jpeg(assertNotNull(JpegSegments.xmpSegment("<x:xmpmeta/>")))
        assertNull(motionPhotoStill(jpeg, videoLength = 7))
    }

    @Test
    fun a_file_that_is_not_a_jpeg_is_refused() {
        assertNull(motionPhotoStill("....ftypheic".encodeToByteArray(), videoLength = 7))
        assertNull(motionPhotoStill(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01), videoLength = 7))
    }

    @Test
    fun a_built_motion_photo_is_taken_apart_again() {
        val video = mp4(bytes = 300)
        val file = assertNotNull(motionPhotoStill(jpeg(exif()), video.size.toLong())) + video

        val range = assertNotNull(locateMotionVideo(assertNotNull(jpegXmp(file)), file))
        assertContentEquals(video, file.copyOfRange(range.first, range.last + 1))
    }

    @Test
    fun the_legacy_offset_alone_locates_the_video() {
        val video = mp4(bytes = 64)
        val xmp = """<rdf:Description GCamera:MicroVideo="1" GCamera:MicroVideoOffset="64"/>"""
        val file = jpeg() + video
        assertEquals(file.size - 64 until file.size, locateMotionVideo(xmp, file))
    }

    @Test
    fun the_element_form_of_a_property_is_read_too() {
        val video = mp4(bytes = 64)
        val xmp = "<GCamera:MicroVideoOffset>64</GCamera:MicroVideoOffset>"
        assertEquals(64, locateMotionVideo(xmp, jpeg() + video)?.count())
    }

    @Test
    fun a_video_wrapped_in_an_mpvd_box_is_found_inside_it() {
        val video = mp4(bytes = 64)
        val box = byteArrayOf(0, 0, 0, 72) + "mpvd".encodeToByteArray() + video
        val file = jpeg() + box
        assertEquals(file.size - 64 until file.size, locateMotionVideo("""GCamera:MicroVideoOffset="72"""", file))
    }

    @Test
    fun an_implausible_or_foreign_trailer_is_no_motion_photo() {
        val file = jpeg() + mp4(bytes = 64)
        assertNull(locateMotionVideo("<x:xmpmeta/>", file), "no motion XMP")
        assertNull(locateMotionVideo("""GCamera:MicroVideoOffset="0"""", file), "empty")
        assertNull(locateMotionVideo("""GCamera:MicroVideoOffset="${file.size}"""", file), "the whole file")
        assertNull(locateMotionVideo("""GCamera:MicroVideoOffset="63"""", file), "no ftyp where it should start")
    }

    @Test
    fun the_presentation_timestamp_is_read_and_minus_one_means_none() {
        assertNull(motionPresentationTimestampUs(MotionPhoto.packet(5)))
        assertEquals(1_500_000, motionPresentationTimestampUs("""Camera:MotionPhotoPresentationTimestampUs="1500000""""))
        assertEquals(42, motionPresentationTimestampUs("""GCamera:MicroVideoPresentationTimestampUs="42""""))
    }

    @Test
    fun an_extension_is_replaced_or_added() {
        assertEquals("IMG_4471.jpg", withExtension("IMG_4471.HEIC", ".jpg"))
        assertEquals("IMG_4471.MOV", withExtension("IMG_4471", ".MOV"))
        assertEquals(".hidden.jpg", withExtension(".hidden", ".jpg"))
    }

    // ---- fixtures: minimal but well-formed JPEG segment streams --------------------------------------------

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private fun jfif() = segment(0xE0, "JFIF\u0000\u0001\u0001\u0000\u0000\u0001\u0000\u0001\u0000\u0000".encodeToByteArray())
    private fun exif() = segment(0xE1, "Exif\u0000\u0000MM\u0000*".encodeToByteArray())
    private val quantTable = segment(0xDB, ByteArray(65) { it.toByte() })
    private val scan = segment(0xDA, ByteArray(10)) + ByteArray(50) { (it * 7).toByte() } +
        byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun jpeg(vararg leading: ByteArray): ByteArray =
        leading.fold(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) { acc, s -> acc + s } + quantTable + scan

    /** Everything from the first non-APP segment on: what a codec must never change. */
    private fun imageData(jpeg: ByteArray): ByteArray {
        val first = assertNotNull(JpegSegments.leading(jpeg)).first { it.marker !in 0xE0..0xEF }
        return jpeg.copyOfRange(first.start, jpeg.size)
    }

    private fun mp4(bytes: Int): ByteArray =
        byteArrayOf(0, 0, 0, 0x18) + "ftypmp42".encodeToByteArray() + ByteArray(bytes - 12) { it.toByte() }
}
