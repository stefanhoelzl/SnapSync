package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceivedPhotoNameTest {

    private val ref = AssetRef("dev-1", AssetId("03C741F2-4FFA-4792-B2E3-076266091091_L0_001"))
    private val key = "03C741F2-4FFA-4792-B2E3-076266091091_L0_001-primary.heic"

    @Test
    fun the_token_is_pinned() {
        // A STORED format: every photo an earlier build marked is matched by recomputing this. The values were
        // computed by an independent implementation of FNV-1a 64 → low 50 bits → RFC 4648 base32, lowercase.
        assertEquals("zfevt6zysw", ReceivedPhotoName.token(ref))
        assertEquals(
            "fim54ck2vz",
            ReceivedPhotoName.token(AssetRef("11111111-2222-3333-4444-555555555555", AssetId("A"))),
        )
    }

    @Test
    fun the_token_tells_refs_apart() {
        assertNotEquals(ReceivedPhotoName.token(ref), ReceivedPhotoName.token(ref.copy(sourceDeviceId = "dev-2")))
        assertNotEquals(ReceivedPhotoName.token(ref), ReceivedPhotoName.token(ref.copy(sourceAssetId = AssetId("B"))))
    }

    @Test
    fun the_senders_name_is_kept_and_marked() {
        assertEquals("IMG_4471.snapsync-zfevt6zysw.HEIC", ReceivedPhotoName.mark("IMG_4471.HEIC", key, ref))
    }

    @Test
    fun an_unenriched_row_is_named_by_its_mark_and_the_keys_extension() {
        // `""` is the manifest's "never enriched" sentinel. No internal key reaches the library any more.
        assertEquals("snapsync-zfevt6zysw.heic", ReceivedPhotoName.mark("", key, ref))
    }

    @Test
    fun a_name_that_already_carries_a_mark_is_marked_once() {
        val marked = ReceivedPhotoName.mark("IMG_4471.snapsync-aaaaaaaaaa.HEIC", key, ref)
        assertEquals("IMG_4471.snapsync-zfevt6zysw.HEIC", marked)
        assertEquals("snapsync-zfevt6zysw.HEIC", ReceivedPhotoName.mark("snapsync-aaaaaaaaaa.HEIC", key, ref))
    }

    @Test
    fun a_name_without_an_extension_is_marked_at_its_end() {
        assertEquals("IMG_4471.snapsync-zfevt6zysw", ReceivedPhotoName.mark("IMG_4471", key, ref))
    }

    @Test
    fun a_name_ending_in_a_dot_has_no_extension_to_keep() {
        assertEquals("IMG_4471.snapsync-zfevt6zysw", ReceivedPhotoName.mark("IMG_4471.", key, ref))
    }

    @Test
    fun the_role_token_never_reaches_the_library() {
        for (role in ResourceRole.entries) {
            val roleKey = uploadKey(ref.sourceAssetId, role, "IMG_4471.HEIC")
            assertTrue(roleKey.contains("-${role.wire}"), "the key under test must carry the role token")
            assertTrue("-${role.wire}" !in ReceivedPhotoName.mark("IMG_4471.HEIC", roleKey, ref))
        }
    }

    @Test
    fun both_resources_of_a_live_photo_carry_one_token() {
        val still = ReceivedPhotoName.mark(
            "IMG_4471.HEIC",
            uploadKey(ref.sourceAssetId, ResourceRole.PRIMARY, "IMG_4471.HEIC"),
            ref,
        )
        val paired = ReceivedPhotoName.mark(
            "IMG_4471.MOV",
            uploadKey(ref.sourceAssetId, ResourceRole.LIVE, "IMG_4471.MOV"),
            ref,
        )
        assertEquals("IMG_4471.snapsync-zfevt6zysw.HEIC", still)
        assertEquals("IMG_4471.snapsync-zfevt6zysw.MOV", paired)
        assertEquals(ReceivedPhotoName.tokenOf(still), ReceivedPhotoName.tokenOf(paired))
    }

    @Test
    fun a_marked_name_reads_back_its_token() {
        assertEquals("zfevt6zysw", ReceivedPhotoName.tokenOf(ReceivedPhotoName.mark("IMG_4471.HEIC", key, ref)))
        assertEquals("zfevt6zysw", ReceivedPhotoName.tokenOf(ReceivedPhotoName.mark("", key, ref)))
    }

    @Test
    fun the_token_survives_renames_around_it() {
        // MediaStore's collision suffix, a user's appended text, an uppercased name.
        assertEquals("zfevt6zysw", ReceivedPhotoName.tokenOf("IMG_4471.snapsync-zfevt6zysw (1).HEIC"))
        assertEquals("zfevt6zysw", ReceivedPhotoName.tokenOf("IMG_4471.snapsync-zfevt6zysw edited.HEIC"))
        assertEquals("zfevt6zysw", ReceivedPhotoName.tokenOf("IMG_4471.SNAPSYNC-ZFEVT6ZYSW.HEIC"))
    }

    @Test
    fun an_unmarked_name_has_no_token() {
        assertNull(ReceivedPhotoName.tokenOf("IMG_4471.HEIC"))
        assertNull(ReceivedPhotoName.tokenOf("IMG_snapsync-zfevt6zysw.HEIC")) // not after a dot or at the start
        assertNull(ReceivedPhotoName.tokenOf("IMG_4471.snapsync-zfevt6zy.HEIC")) // too short
        assertNull(ReceivedPhotoName.tokenOf("IMG_4471.snapsync-zfevt6zyswx.HEIC")) // too long
    }
}
