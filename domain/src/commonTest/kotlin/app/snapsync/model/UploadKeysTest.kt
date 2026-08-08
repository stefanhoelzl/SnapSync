package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UploadKeysTest {

    @Test
    fun key_is_assetid_role_and_lowercased_extension() {
        assertEquals("ASSET1-primary.heic", uploadKey("ASSET1", ResourceRole.PRIMARY, "IMG_0001.HEIC"))
        assertEquals("ASSET1-live.mov", uploadKey("ASSET1", ResourceRole.LIVE, "IMG_0001.MOV"))
    }

    @Test
    fun primary_and_live_of_one_asset_yield_distinct_keys() {
        val primary = uploadKey("X", ResourceRole.PRIMARY, "IMG.HEIC")
        val live = uploadKey("X", ResourceRole.LIVE, "IMG.MOV")
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
        // embedded '-' (a PHAsset localIdentifier may contain '-'; the role token never does).
        for (assetId in listOf("ASSET1", "3F2A-4B1C/L0/001", "a-b-c", "no-dash".replace("-", ""))) {
            val normalized = assetId.replace('/', '_')
            assertEquals(normalized, assetIdFromUploadKey(uploadKey(normalized, ResourceRole.PRIMARY, "IMG.HEIC")))
            assertEquals(normalized, assetIdFromUploadKey(uploadKey(normalized, ResourceRole.LIVE, "IMG.MOV")))
        }
    }

    @Test
    fun assetid_and_role_recover_the_same_key_segments() {
        val key = uploadKey("X-9", ResourceRole.LIVE, "IMG.MOV")
        assertEquals("X-9", assetIdFromUploadKey(key))
        assertEquals(ResourceRole.LIVE, roleFromUploadKey(key))
    }

    @Test
    fun normalize_replaces_every_slash_with_underscore() {
        // The load-bearing suppression transform: discovery and the download importer MUST agree on it.
        assertEquals("ABC_L0_001", normalizeAssetId("ABC/L0/001"))
        assertEquals("plain", normalizeAssetId("plain")) // no slash → unchanged
    }

    @Test
    fun a_slash_containing_id_round_trips_through_normalize_then_key_then_parse() {
        // discovery: normalize the raw localIdentifier → derive the key → the key parses back to the
        // SAME normalized assetId (which is what the suppression set — a normalized createdLocalId —
        // must contain). This is the end-to-end discovery-side identity the echo-suppression relies on.
        val normalized = normalizeAssetId("ABC/L0/001")
        assertEquals("ABC_L0_001", normalized)
        assertEquals(normalized, assetIdFromUploadKey(uploadKey(normalized, ResourceRole.PRIMARY, "IMG.HEIC")))
    }
}
