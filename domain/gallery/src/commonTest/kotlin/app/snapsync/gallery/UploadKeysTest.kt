package app.snapsync.gallery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UploadKeysTest {

    @Test
    fun key_is_assetid_role_and_lowercased_extension() {
        assertEquals("ASSET1-primary.heic", uploadKey("ASSET1", ResourceRole.PRIMARY, "IMG_0001.HEIC"))
        assertEquals("ASSET1-motion.mov", uploadKey("ASSET1", ResourceRole.MOTION, "IMG_0001.MOV"))
    }

    @Test
    fun primary_and_motion_of_one_asset_yield_distinct_keys() {
        val primary = uploadKey("X", ResourceRole.PRIMARY, "IMG.HEIC")
        val motion = uploadKey("X", ResourceRole.MOTION, "IMG.MOV")
        assertEquals("X-primary.heic", primary)
        assertEquals("X-motion.mov", motion)
    }

    @Test
    fun original_resource_types_map_to_roles() {
        assertEquals(ResourceRole.PRIMARY, resourceRole(1L)) // photo
        assertEquals(ResourceRole.PRIMARY, resourceRole(2L)) // video
        assertEquals(ResourceRole.PRIMARY, resourceRole(3L)) // audio
        assertEquals(ResourceRole.MOTION, resourceRole(9L)) // pairedVideo
    }

    @Test
    fun edit_artifacts_raw_alternate_and_unknown_types_are_dropped() {
        // 4 alternatePhoto (RAW), 5 fullSizePhoto, 6 fullSizeVideo, 7 adjustmentData,
        // 8 adjustmentBasePhoto, 10 fullSizePairedVideo, and any future/unknown type.
        for (type in listOf(4L, 5L, 6L, 7L, 8L, 10L, 99L)) {
            assertNull(resourceRole(type), "type $type should be dropped")
        }
    }

    @Test
    fun an_edited_live_photo_yields_only_its_originals() {
        // An edited Live Photo exposes its originals (photo + pairedVideo) alongside edit artifacts
        // (a full-size render, adjustment data, a full-size paired video). Only the originals survive.
        val exposed = listOf(1L, 9L, 5L, 7L, 10L)
        assertEquals(listOf(ResourceRole.PRIMARY, ResourceRole.MOTION), exposed.mapNotNull { resourceRole(it) })
    }

    @Test
    fun missing_extension_falls_back_to_bin() {
        assertEquals("bin", fileExtension("noextension"))
        assertEquals("dng", fileExtension("Photo.DNG"))
    }
}
