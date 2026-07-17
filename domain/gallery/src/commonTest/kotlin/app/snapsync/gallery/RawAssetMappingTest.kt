package app.snapsync.gallery

import app.snapsync.feature.upload.ResourceEnumerator
import app.snapsync.model.MEDIA_TYPE_IMAGE
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.RESOURCE_META_HAS_ADJUSTMENTS
import app.snapsync.model.RESOURCE_META_MEDIA_SUBTYPES
import app.snapsync.model.RESOURCE_META_MEDIA_TYPE
import app.snapsync.model.RESOURCE_META_MIME
import app.snapsync.model.RESOURCE_META_ORIGINAL_FILENAME
import app.snapsync.model.RESOURCE_META_PIXEL_HEIGHT
import app.snapsync.model.RESOURCE_META_PIXEL_WIDTH
import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.model.SUBTYPE_SCREENSHOT
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.excludedAssetIds
import app.snapsync.model.resourcesFrom

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The fan-out orchestration ([resourcesFrom]) exercised off-device — the coverage Move A unlocks. This
 * loop (role filter, `'/'→'_'` normalization, `uploadKey`, metadata assembly) previously lived only in
 * the iOS enumerator; here it runs on JVM and the iOS simulator against a fake raw-asset walk.
 */
class RawAssetMappingTest {

    private fun raw(
        type: Long,
        uti: String = "public.jpeg",
        mime: String = "image/jpeg",
        name: String = "IMG.JPG",
        handle: Any = Unit,
    ) = RawResource(type = type, contentTypeUti = uti, mimeContentType = mime, originalFilename = name, handle = handle)

    @Test
    fun maps_originals_only_with_role_keys_normalization_and_metadata() {
        val asset = RawAsset(
            assetId = "ABC/L0/001", // raw localIdentifier with '/'
            creationDate = "2026-07-01T00:00:00Z",
            rawResources = listOf(
                raw(1L, uti = "public.heic", mime = "image/heic", name = "IMG_0001.HEIC"), // photo -> primary
                raw(9L, uti = "com.apple.quicktime-movie", mime = "video/quicktime", name = "IMG_0001.MOV"), // pairedVideo -> live
                raw(5L), // fullSizePhoto -> dropped (edit artifact)
                raw(7L), // adjustmentData -> dropped
            ),
        )

        val resources = resourcesFrom(listOf(asset))

        assertEquals(2, resources.size, "only the two originals survive the role filter")
        val primary = resources.first { it.filename.endsWith("-primary.heic") }
        assertEquals("ABC_L0_001", primary.assetId, "assetId normalized '/'->'_'")
        assertEquals("ABC_L0_001-primary.heic", primary.filename)
        assertEquals("public.heic", primary.contentType)
        assertEquals("2026-07-01T00:00:00Z", primary.metadata[RESOURCE_META_CREATION_DATE])
        assertEquals("IMG_0001.HEIC", primary.metadata[RESOURCE_META_ORIGINAL_FILENAME])
        assertEquals("image/heic", primary.metadata[RESOURCE_META_MIME])
        val live = resources.first { it.filename.endsWith("-live.mov") }
        assertEquals("ABC_L0_001-live.mov", live.filename)
        assertEquals("video/quicktime", live.metadata[RESOURCE_META_MIME])
    }

    @Test
    fun origin_facts_survive_the_mapping_onto_every_resource_of_the_asset() {
        val asset = RawAsset(
            assetId = "ABC/L0/001",
            creationDate = "2026-07-01T00:00:00Z",
            rawResources = listOf(
                raw(1L, uti = "public.heic", mime = "image/heic", name = "IMG_0001.HEIC"),
                raw(9L, uti = "com.apple.quicktime-movie", mime = "video/quicktime", name = "IMG_0001.MOV"),
            ),
            mediaSubtypes = SUBTYPE_SCREENSHOT,
            mediaType = MEDIA_TYPE_IMAGE,
            pixelWidth = 750,
            pixelHeight = 1334,
            hasAdjustments = true,
        )

        val resources = resourcesFrom(listOf(asset))

        // Every resource of the asset carries the asset's facts — the policy decides per-asset, so the
        // paired video must not be left without the facts that condemn (or save) its primary.
        assertEquals(2, resources.size)
        for (r in resources) {
            assertEquals("${SUBTYPE_SCREENSHOT}", r.metadata[RESOURCE_META_MEDIA_SUBTYPES])
            assertEquals("${MEDIA_TYPE_IMAGE}", r.metadata[RESOURCE_META_MEDIA_TYPE])
            assertEquals("750", r.metadata[RESOURCE_META_PIXEL_WIDTH])
            assertEquals("1334", r.metadata[RESOURCE_META_PIXEL_HEIGHT])
            assertEquals("true", r.metadata[RESOURCE_META_HAS_ADJUSTMENTS])
        }
    }

    @Test
    fun the_walk_stays_decision_free_a_screenshot_is_mapped_not_dropped() = runTest {
        // The walk and the mapping carry facts; they never exclude. A screenshot must cross this seam intact
        // — the authoritative filter lives downstream in the upload cycle, and putting it here instead would
        // hide it from `:capability:upload`'s tests and from the status total.
        val screenshot = RawAsset(
            assetId = "S1",
            creationDate = "2026-07-01T00:00:00Z",
            rawResources = listOf(raw(1L, uti = "public.png", mime = "image/png", name = "IMG_0002.PNG")),
            mediaSubtypes = SUBTYPE_SCREENSHOT,
            pixelWidth = 750,
            pixelHeight = 1334,
        )
        val source = InMemoryRawAssetSource(listOf(screenshot))

        val resources = ResourceEnumerator(source).enumerate("2026-01-01T00:00:00Z")

        assertEquals(1, resources.size, "the walk emits the screenshot as a fact — it does not drop it")
        assertEquals(setOf("S1"), excludedAssetIds(resources), "…and the policy is what excludes it")
    }

    @Test
    fun opaque_handle_rides_into_resource_data_uninterpreted() {
        val marker = Any()
        val resources = resourcesFrom(listOf(RawAsset("A", "", listOf(raw(1L, handle = marker)))))
        assertEquals(marker, resources.single().data, "the PHAssetResource handle crosses uninterpreted")
    }

    @Test
    fun mapped_filename_round_trips_to_the_normalized_assetid() {
        // The discovery->key->parse identity echo-suppression + reconstruct rely on (change 1's parser).
        val resources = resourcesFrom(listOf(RawAsset("ABC/L0/001", "", listOf(raw(1L, name = "x.JPG")))))
        assertEquals("ABC_L0_001", assetIdFromUploadKey(resources.single().filename))
    }

    @Test
    fun enumerator_composes_walk_then_map_over_the_fake_source() = runTest {
        val source = InMemoryRawAssetSource(
            listOf(
                RawAsset("A", IN_SCOPE, listOf(raw(1L, name = "a.JPG"))),
                RawAsset("B", IN_SCOPE, listOf(raw(1L, name = "b.JPG"))),
            ),
        )
        val enumerator = ResourceEnumerator(source)

        assertEquals(listOf("A-primary.jpg", "B-primary.jpg"), enumerator.enumerate(CUTOFF).map { it.filename })
        assertEquals(
            listOf("A-primary.jpg"),
            enumerator.resources(listOf("A"), CUTOFF).map { it.filename },
            "incremental walk",
        )
    }

    @Test
    fun the_bounded_walk_excludes_assets_captured_before_the_bound() = runTest {
        // There is no unbounded walk (capability `photo-selection-policy`): the whole-library enumeration cost
        // one synchronous PhotoKit round-trip per asset, and a membership always has a cutoff to scope it.
        val source = InMemoryRawAssetSource(
            listOf(
                RawAsset("OLD", "2000-01-01T00:00:00Z", listOf(raw(1L, name = "old.JPG"))),
                RawAsset("NEW", IN_SCOPE, listOf(raw(1L, name = "new.JPG"))),
            ),
        )

        assertEquals(listOf("NEW-primary.jpg"), ResourceEnumerator(source).enumerate(CUTOFF).map { it.filename })
    }

    @Test
    fun the_incremental_walk_skips_a_changed_asset_that_is_out_of_scope() = runTest {
        // A change feed says what CHANGED, not what is in SCOPE. An iCloud sync or bulk import hands back
        // decades-old assets; fetching each one's resources to then drop it on capture date cost 166 s for
        // ~1500 assets on an iPhone SE2 (extension hard-capped at ~3 min). The bound rejects them first.
        val source = InMemoryRawAssetSource(
            listOf(
                RawAsset("OLD", "2000-01-01T00:00:00Z", listOf(raw(1L, name = "old.JPG"))),
                RawAsset("NEW", IN_SCOPE, listOf(raw(1L, name = "new.JPG"))),
            ),
        )
        val enumerator = ResourceEnumerator(source)

        val changed = enumerator.resources(listOf("OLD", "NEW"), CUTOFF).map { it.filename }
        assertEquals(listOf("NEW-primary.jpg"), changed, "a changed-but-out-of-scope asset is skipped")
    }

    @Test
    fun an_undated_asset_is_before_every_bound() = runTest {
        // An empty `creationDate` sorts before any non-empty cutoff, so an undated asset is never in scope.
        val source = InMemoryRawAssetSource(listOf(RawAsset("U", "", listOf(raw(1L, name = "u.JPG")))))

        assertEquals(emptyList(), ResourceEnumerator(source).enumerate(CUTOFF).map { it.filename })
    }
}

private const val CUTOFF = "2026-01-01T00:00:00Z"
private const val IN_SCOPE = "2026-06-01T10:00:00Z"
