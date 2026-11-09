package app.snapsync.domain.receiving

import app.snapsync.domain.asset.CaptureDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A served photo, from what each of its resources' names came to (decision record `changes/seal-file-names`, D6): read
 * when every name reads; left out — never received, the page going on — when a sealed one does not open; and a page
 * this build cannot go on from when one is malformed.
 */
class ServedPhotoTest {
    private val named = ServedResource.Named(
        UnionResource("A-primary.heic", "u/A", "primary", "image/heic", SenderFilename.parse("IMG_1.HEIC")!!),
    )

    @Test
    fun a_photo_whose_every_name_reads_is_read_with_its_resources() {
        val read = assertIs<ServedPhoto.Read>(ServedPhoto.of(ref("A"), CaptureDate.Undated, listOf(named)))
        assertEquals(ref("A"), read.photo.ref)
        assertEquals(listOf("IMG_1.HEIC"), read.photo.resources.map { it.originalFilename.value })
    }

    @Test
    fun a_photo_with_a_sealed_name_that_does_not_open_is_left_out_saying_why() {
        val left = assertIs<ServedPhoto.Left>(
            ServedPhoto.of(ref("A"), CaptureDate.Undated, listOf(named, ServedResource.Unopened("another key"))),
        )
        assertEquals(ref("A"), left.ref)
        assertEquals(listOf("another key"), left.reasons)
    }

    @Test
    fun a_malformed_resource_makes_its_photo_malformed_whatever_else_it_holds() {
        assertEquals(
            ServedPhoto.Malformed,
            ServedPhoto.of(
                ref("A"),
                CaptureDate.Undated,
                listOf(ServedResource.Unopened("altered"), ServedResource.Malformed),
            ),
        )
    }

    @Test
    fun a_page_serves_its_read_photos_drops_those_left_out_and_is_none_with_a_malformed_one() {
        val read = ServedPhoto.of(ref("A"), CaptureDate.Undated, listOf(named))
        val left = ServedPhoto.Left(ref("B"), listOf("altered"))

        assertEquals(listOf(ref("A")), ServedPhoto.pageOf(listOf(read, left))?.map { it.ref })
        assertNull(ServedPhoto.pageOf(listOf(read, ServedPhoto.Malformed)))
    }

    @Test
    fun a_served_name_is_named_unopened_or_malformed() {
        val name = SenderFilename.parse("IMG_1.HEIC")!!
        assertEquals(name, ServedFileName.Named(name).name)
        assertEquals("altered", ServedFileName.Unopened("altered").reason)
        assertIs<ServedFileName>(ServedFileName.Malformed)
    }
}
