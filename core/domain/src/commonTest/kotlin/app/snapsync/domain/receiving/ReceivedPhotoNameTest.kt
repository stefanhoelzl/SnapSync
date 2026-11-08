package app.snapsync.domain.receiving

import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ReceivedPhotoNameTest {

    private val ref = AssetRef(testDeviceId("11111111-2222-3333-4444-555555555555"), testAssetId("A"))

    private fun mark(name: String, of: AssetRef = ref): String =
        ReceivedPhotoName.mark(checkNotNull(SenderFilename.parse(name)), of)

    @Test
    fun the_token_is_pinned() {
        // A STORED format: every photo an earlier build marked is matched by recomputing this. The values were
        // computed by an independent implementation of FNV-1a 64 → low 50 bits → RFC 4648 base32, lowercase.
        assertEquals("fim54ck2vz", ReceivedPhotoName.token(ref))
    }

    @Test
    fun the_token_tells_refs_apart() {
        assertNotEquals(
            ReceivedPhotoName.token(ref),
            ReceivedPhotoName.token(ref.copy(sourceDeviceId = testDeviceId("dev-2"))),
        )
        assertNotEquals(
            ReceivedPhotoName.token(ref),
            ReceivedPhotoName.token(ref.copy(sourceAssetId = testAssetId("B"))),
        )
    }

    @Test
    fun the_senders_name_is_kept_and_marked() {
        assertEquals("IMG_4471.snapsync-fim54ck2vz.HEIC", mark("IMG_4471.HEIC"))
    }

    @Test
    fun a_name_that_already_carries_a_mark_is_marked_once() {
        assertEquals("IMG_4471.snapsync-fim54ck2vz.HEIC", mark("IMG_4471.snapsync-aaaaaaaaaa.HEIC"))
        assertEquals("snapsync-fim54ck2vz.HEIC", mark("snapsync-aaaaaaaaaa.HEIC"))
    }

    @Test
    fun a_name_without_an_extension_is_marked_at_its_end() {
        assertEquals("IMG_4471.snapsync-fim54ck2vz", mark("IMG_4471"))
    }

    @Test
    fun a_name_ending_in_a_dot_has_no_extension_to_keep() {
        assertEquals("IMG_4471.snapsync-fim54ck2vz", mark("IMG_4471."))
    }

    @Test
    fun both_resources_of_a_live_photo_carry_one_token() {
        val still = mark("IMG_4471.HEIC")
        val paired = mark("IMG_4471.MOV")
        assertEquals("IMG_4471.snapsync-fim54ck2vz.HEIC", still)
        assertEquals("IMG_4471.snapsync-fim54ck2vz.MOV", paired)
        assertEquals(ReceivedPhotoName.tokenOf(still), ReceivedPhotoName.tokenOf(paired))
    }

    @Test
    fun a_marked_name_reads_back_its_token() {
        assertEquals("fim54ck2vz", ReceivedPhotoName.tokenOf(mark("IMG_4471.HEIC")))
    }

    @Test
    fun the_bare_mark_earlier_builds_wrote_still_reads_back_its_token() {
        // Earlier builds named a photo whose sender filename was unknown `snapsync-<token>.<ext>`. No build writes
        // that form any more, but libraries keep such photos for good, so it is read for good.
        assertEquals("fim54ck2vz", ReceivedPhotoName.tokenOf("snapsync-fim54ck2vz.heic"))
    }

    @Test
    fun the_token_survives_renames_around_it() {
        // MediaStore's collision suffix, a user's appended text, an uppercased name.
        assertEquals("fim54ck2vz", ReceivedPhotoName.tokenOf("IMG_4471.snapsync-fim54ck2vz (1).HEIC"))
        assertEquals("fim54ck2vz", ReceivedPhotoName.tokenOf("IMG_4471.snapsync-fim54ck2vz edited.HEIC"))
        assertEquals("fim54ck2vz", ReceivedPhotoName.tokenOf("IMG_4471.SNAPSYNC-FIM54CK2VZ.HEIC"))
    }

    @Test
    fun an_unmarked_name_has_no_token() {
        assertNull(ReceivedPhotoName.tokenOf("IMG_4471.HEIC"))
        assertNull(ReceivedPhotoName.tokenOf("IMG_snapsync-fim54ck2vz.HEIC")) // not after a dot or at the start
        assertNull(ReceivedPhotoName.tokenOf("IMG_4471.snapsync-zfevt6zy.HEIC")) // too short
        assertNull(ReceivedPhotoName.tokenOf("IMG_4471.snapsync-fim54ck2vzx.HEIC")) // too long
    }
}
