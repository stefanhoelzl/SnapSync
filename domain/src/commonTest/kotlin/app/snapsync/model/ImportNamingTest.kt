package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportNamingTest {

    @Test
    fun the_capturing_devices_name_wins_over_the_storage_key() {
        assertEquals(
            "IMG_4471.HEIC",
            importFilename("IMG_4471.HEIC", "03C741F2-4FFA-4792-B2E3-076266091091_L0_001-primary.heic"),
        )
    }

    @Test
    fun an_unenriched_row_falls_back_to_the_storage_key() {
        // `""` is the manifest's "never enriched" sentinel (pre-5.sqm rows, and rows the re-join
        // reconcile seeded from a filename listing). The key is what the bytes are actually called.
        val key = "03C741F2-4FFA-4792-B2E3-076266091091_L0_001-primary.heic"
        assertEquals(key, importFilename("", key))
    }

    @Test
    fun the_name_never_comes_back_empty() {
        // An unnamed PHAssetResource is worse than an ugly one — there is no third answer.
        for ((original, key) in listOf("" to "K-primary.heic", "IMG.HEIC" to "K-primary.heic")) {
            assertTrue(importFilename(original, key).isNotEmpty())
        }
    }

    @Test
    fun the_role_token_is_gone_from_a_named_import() {
        // The reported symptom, stated as an assertion: given a name, nothing of the key's internal
        // shape — the assetId, the `-primary`/`-live` role token — reaches the photo library.
        for (role in ResourceRole.entries) {
            val key = uploadKey("03C741F2-4FFA-4792-B2E3-076266091091_L0_001", role, "IMG_4471.HEIC")
            assertTrue(key.contains("-${role.wire}"), "the key under test must carry the role token")
            assertEquals("IMG_4471.HEIC", importFilename("IMG_4471.HEIC", key))
        }
    }

    @Test
    fun each_resource_of_a_live_photo_keeps_its_own_name() {
        // Both resources of one asset are added in a single creation request; the still and the paired
        // video must not collapse onto one name.
        val still = importFilename("IMG_4471.HEIC", uploadKey("A", ResourceRole.PRIMARY, "IMG_4471.HEIC"))
        val paired = importFilename("IMG_4471.MOV", uploadKey("A", ResourceRole.LIVE, "IMG_4471.MOV"))
        assertEquals("IMG_4471.HEIC", still)
        assertEquals("IMG_4471.MOV", paired)
    }
}
