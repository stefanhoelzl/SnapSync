package app.snapsync.gallery

import kotlin.test.Test
import kotlin.test.assertEquals

class UploadKeysTest {

    @Test
    fun key_is_assetid_kind_and_lowercased_extension() {
        assertEquals("ASSET1-ios.photo.heic", uploadKey("ASSET1", 1L, "IMG_0001.HEIC"))
    }

    @Test
    fun distinct_resource_kinds_of_one_asset_yield_distinct_keys() {
        val photo = uploadKey("X", 1L, "IMG.HEIC")
        val edited = uploadKey("X", 5L, "FullSizeRender.jpeg")
        assertEquals("X-ios.photo.heic", photo)
        assertEquals("X-ios.fullSizePhoto.jpeg", edited)
    }

    @Test
    fun known_resource_types_map_to_open_kind_strings() {
        assertEquals("ios.photo", resourceKind(1L))
        assertEquals("ios.fullSizePhoto", resourceKind(5L))
        assertEquals("ios.pairedVideo", resourceKind(9L))
    }

    @Test
    fun unknown_resource_type_falls_back_deterministically() {
        assertEquals("ios.type99", resourceKind(99L))
    }

    @Test
    fun missing_extension_falls_back_to_bin() {
        assertEquals("bin", fileExtension("noextension"))
        assertEquals("dng", fileExtension("Photo.DNG"))
    }
}
