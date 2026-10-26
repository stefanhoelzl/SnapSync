package app.snapsync.model

import app.snapsync.domain.asset.ResourceRole
import app.snapsync.testids.testAssetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UploadKeysTest {

    @Test
    fun key_is_assetid_role_and_lowercased_extension() {
        assertEquals("ASSET1-primary.heic", uploadKey(testAssetId("ASSET1"), ResourceRole.PRIMARY, "IMG_0001.HEIC"))
        assertEquals("ASSET1-live.mov", uploadKey(testAssetId("ASSET1"), ResourceRole.LIVE, "IMG_0001.MOV"))
    }

    @Test
    fun primary_and_live_of_one_asset_yield_distinct_keys() {
        val primary = uploadKey(testAssetId("X"), ResourceRole.PRIMARY, "IMG.HEIC")
        val live = uploadKey(testAssetId("X"), ResourceRole.LIVE, "IMG.MOV")
        assertEquals("X-primary.heic", primary)
        assertEquals("X-live.mov", live)
    }

    // The platform resource-type table lives in `:adapter:ios:ext-safe`
    // (`photoKitResourceKind`) with the `PHAssetResourceType` constants it reads. Asserting it here
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
        for (id in listOf("ASSET1", "3F2A-4B1C_L0_001", "a-b-c", "nodash").map(::testAssetId)) {
            assertEquals(id, assetIdFromUploadKey(uploadKey(id, ResourceRole.PRIMARY, "IMG.HEIC")))
            assertEquals(id, assetIdFromUploadKey(uploadKey(id, ResourceRole.LIVE, "IMG.MOV")))
        }
    }

    @Test
    fun assetid_and_role_recover_the_same_key_segments() {
        val key = uploadKey(testAssetId("X-9"), ResourceRole.LIVE, "IMG.MOV")
        assertEquals(testAssetId("X-9"), assetIdFromUploadKey(key))
        assertEquals(ResourceRole.LIVE, roleFromUploadKey(key))
    }

    @Test
    fun `a key that names no canonical asset is refused`() {
        assertFailsWith<IllegalArgumentException> { assetIdFromUploadKey("a b-primary.heic") }
    }
}
