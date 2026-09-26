package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UploadKeysTest {

    @Test
    fun key_is_assetid_role_and_lowercased_extension() {
        assertEquals("ASSET1-primary.heic", uploadKey(AssetId("ASSET1"), ResourceRole.PRIMARY, "IMG_0001.HEIC"))
        assertEquals("ASSET1-live.mov", uploadKey(AssetId("ASSET1"), ResourceRole.LIVE, "IMG_0001.MOV"))
    }

    @Test
    fun primary_and_live_of_one_asset_yield_distinct_keys() {
        val primary = uploadKey(AssetId("X"), ResourceRole.PRIMARY, "IMG.HEIC")
        val live = uploadKey(AssetId("X"), ResourceRole.LIVE, "IMG.MOV")
        assertEquals("X-primary.heic", primary)
        assertEquals("X-live.mov", live)
    }

    // The platform resource-type → role table moved to `:adapter:ios:ext-safe`
    // (`photoKitResourceRole`) with the `PHAssetResourceType` constants it reads. Asserting it here
    // meant asserting bare integers against bare integers: nothing in a JVM run could disagree, and
    // an ABI table written in literals is invisible to every gate. Its tests moved with it and now
    // name Apple's constants, including the edited-Live-Photo case.

    @Test
    fun missing_extension_falls_back_to_bin() {
        assertEquals("bin", fileExtension("noextension"))
        assertEquals("dng", fileExtension("Photo.DNG"))
    }

    @Test
    fun assetid_round_trips_through_the_upload_key() {
        // assetIdFromUploadKey is the exact inverse of uploadKey — for assetIds with AND without an
        // embedded '-' (a PhotoKit-minted id contains '-'; the role token never does).
        for (id in listOf("ASSET1", "3F2A-4B1C_L0_001", "a-b-c", "nodash").map(::AssetId)) {
            assertEquals(id, assetIdFromUploadKey(uploadKey(id, ResourceRole.PRIMARY, "IMG.HEIC")))
            assertEquals(id, assetIdFromUploadKey(uploadKey(id, ResourceRole.LIVE, "IMG.MOV")))
        }
    }

    @Test
    fun assetid_and_role_recover_the_same_key_segments() {
        val key = uploadKey(AssetId("X-9"), ResourceRole.LIVE, "IMG.MOV")
        assertEquals(AssetId("X-9"), assetIdFromUploadKey(key))
        assertEquals(ResourceRole.LIVE, roleFromUploadKey(key))
    }
}
