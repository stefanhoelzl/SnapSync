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

    @Test
    fun original_resource_types_map_to_roles() {
        assertEquals(ResourceRole.PRIMARY, resourceRole(1L)) // photo
        assertEquals(ResourceRole.PRIMARY, resourceRole(2L)) // video
        assertEquals(ResourceRole.PRIMARY, resourceRole(3L)) // audio
        assertEquals(ResourceRole.LIVE, resourceRole(9L)) // pairedVideo
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
        assertEquals(listOf(ResourceRole.PRIMARY, ResourceRole.LIVE), exposed.mapNotNull { resourceRole(it) })
    }

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
