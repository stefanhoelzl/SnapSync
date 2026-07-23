package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The origin-exclusion matrix (capability `photo-selection-policy`). Every rule is checked in both
 * directions — what it excludes *and* what it must not — because the expensive failure here is the false
 * drop: an event photo that silently never uploads.
 */
class SelectionPolicyTest {

    /** One asset, one primary resource, with the origin facts the enumerator would have carried across. */
    private fun asset(
        id: String,
        subtypes: Long = SUBTYPE_NONE,
        mediaType: Long = MEDIA_TYPE_IMAGE,
        width: Long = 4032,
        height: Long = 3024,
        adjusted: Boolean = false,
        mime: String = "image/heic",
    ) = listOf(
        Resource(
            filename = "$id-primary.heic",
            assetId = id,
            contentType = "public.heic",
            metadata = mapOf(
                RESOURCE_META_CREATION_DATE to "2026-07-01T00:00:00Z",
                RESOURCE_META_MIME to mime,
                RESOURCE_META_MEDIA_SUBTYPES to subtypes.toString(),
                RESOURCE_META_MEDIA_TYPE to mediaType.toString(),
                RESOURCE_META_PIXEL_WIDTH to width.toString(),
                RESOURCE_META_PIXEL_HEIGHT to height.toString(),
                RESOURCE_META_HAS_ADJUSTMENTS to adjusted.toString(),
            ),
            data = Unit,
        ),
    )

    /**
     * The ids the ORIGIN rules exclude. Expressed as "everything the one admission did not admit", over a
     * policy whose capture-date floor is empty (admitting every date) — so this matrix isolates the origin
     * rules from the range, exactly as it did when they were a separate function.
     */
    private fun excluded(resources: List<Resource>): Set<String> {
        val policy = SelectionPolicy.from(includesUpload = true, cutoff = captureCutoff(""), ceiling = null)
        return resources.mapTo(mutableSetOf()) { it.assetId } - policy.admittedAssetIds(resources)
    }

    // ── Subtypes ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun screenshot_is_excluded() {
        assertEquals(setOf("A"), excluded(asset("A", subtypes = SUBTYPE_SCREENSHOT)))
    }

    @Test
    fun screen_recording_is_excluded() {
        assertEquals(setOf("A"), excluded(asset("A", subtypes = SUBTYPE_SCREEN_RECORDING, mediaType = MEDIA_TYPE_VIDEO)))
    }

    @Test
    fun a_camera_photo_carrying_other_subtypes_is_admitted() {
        // Panorama (1<<0), HDR (1<<1), live (1<<3), depth (1<<4) are all camera captures. Only the two
        // excluded bits may exclude — a mask that caught these would delete real event photos.
        val cameraSubtypes = (1L shl 0) or (1L shl 1) or (1L shl 3) or (1L shl 4)
        assertTrue(excluded(asset("A", subtypes = cameraSubtypes)).isEmpty())
    }

    // ── GIFs ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_gif_is_excluded() {
        assertEquals(setOf("A"), excluded(asset("A", mime = MIME_GIF)))
    }

    // ── Resolution floors ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_compressed_received_image_is_excluded() {
        // WhatsApp's 1600-long-edge cap → 1600x1200 = 1.92 MP, under the 3 MP floor.
        assertEquals(setOf("A"), excluded(asset("A", width = 1600, height = 1200)))
    }

    @Test
    fun a_camera_photo_is_admitted() {
        assertTrue(excluded(asset("A", width = 4032, height = 3024)).isEmpty())
    }

    @Test
    fun a_cropped_camera_photo_below_the_floor_is_admitted() {
        // The `hasAdjustments` guard. Without it, every heavy crop would silently vanish from the event.
        assertTrue(excluded(asset("A", width = 1000, height = 800, adjusted = true)).isEmpty())
    }

    @Test
    fun a_1080p_recording_is_admitted() {
        // THE load-bearing case: 1920x1080 = 2.07 MP is BELOW the 3 MP image floor. A single shared floor
        // would silently drop every 1080p video — and 1080p is the iOS capture default.
        assertTrue(excluded(asset("A", mediaType = MEDIA_TYPE_VIDEO, width = 1920, height = 1080)).isEmpty())
    }

    @Test
    fun a_compressed_received_video_is_excluded() {
        assertEquals(setOf("A"), excluded(asset("A", mediaType = MEDIA_TYPE_VIDEO, width = 848, height = 480)))
    }

    // ── Admit on doubt ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun an_asset_with_unknown_dimensions_is_admitted() {
        // Missing facts must never condemn an asset. A zero/absent dimension is "we don't know", not "tiny".
        assertTrue(excluded(asset("A", width = 0, height = 0)).isEmpty())
    }

    @Test
    fun an_asset_with_no_origin_metadata_at_all_is_admitted() {
        val bare = listOf(
            Resource(
                filename = "A-primary.heic",
                assetId = "A",
                contentType = "public.heic",
                metadata = mapOf(RESOURCE_META_CREATION_DATE to "2026-07-01T00:00:00Z"),
                data = Unit,
            ),
        )
        assertTrue(excluded(bare).isEmpty(), "absent facts admit — a fake or a legacy row must not vanish")
    }

    // ── Per-asset, not per-resource ───────────────────────────────────────────────────────────────────

    @Test
    fun an_excluded_asset_takes_all_of_its_resources_with_it() {
        // A live photo's primary + paired video. If the policy filtered per-resource on MIME, a GIF's
        // primary would be dropped while its sibling survived as an orphan whose bytes nothing uploads.
        val meta = mapOf(
            RESOURCE_META_CREATION_DATE to "2026-07-01T00:00:00Z",
            RESOURCE_META_MEDIA_SUBTYPES to SUBTYPE_SCREENSHOT.toString(),
            RESOURCE_META_PIXEL_WIDTH to "4032",
            RESOURCE_META_PIXEL_HEIGHT to "3024",
        )
        val both = listOf(
            Resource("A-primary.heic", "A", "public.heic", metadata = meta, data = Unit),
            Resource("A-live.mov", "A", "com.apple.quicktime-movie", metadata = meta, data = Unit),
        )
        assertEquals(setOf("A"), excluded(both), "the asset is excluded, so both of its resources go")
    }

    @Test
    fun admitted_and_excluded_assets_are_separated_correctly() {
        val resources = asset("KEEP") + asset("SHOT", subtypes = SUBTYPE_SCREENSHOT) +
            asset("SMALL", width = 800, height = 600) + asset("GIF", mime = MIME_GIF)
        assertEquals(setOf("SHOT", "SMALL", "GIF"), excluded(resources))
    }
}
