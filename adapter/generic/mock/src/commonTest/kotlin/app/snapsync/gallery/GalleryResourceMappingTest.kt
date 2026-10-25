package app.snapsync.gallery

import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.format.TimestampText
import app.snapsync.mock.InMemoryResource
import app.snapsync.mock.LibraryAssets
import app.snapsync.mock.inMemoryGallery
import app.snapsync.model.Candidate
import app.snapsync.model.CandidateRead
import app.snapsync.model.EventPhotoSet
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.RESOURCE_META_IS_EDITED
import app.snapsync.model.RESOURCE_META_IS_SCREENSHOT
import app.snapsync.model.RESOURCE_META_IS_SCREEN_RECORDING
import app.snapsync.model.RESOURCE_META_IS_VIDEO
import app.snapsync.model.RESOURCE_META_MIME
import app.snapsync.model.RESOURCE_META_ORIGINAL_FILENAME
import app.snapsync.model.RESOURCE_META_PIXEL_AREA
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.selectionRulesFor
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ResourceKind
import app.snapsync.services.gallery.CandidateSource
import app.snapsync.services.gallery.GalleryCandidateSource
import app.snapsync.services.gallery.galleryHandle
import app.snapsync.services.gallery.resourcesFrom
import app.snapsync.testids.testAssetId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The library read over an in-memory gallery holding [assets] — the service the app composes over the gallery. */
private fun librarySource(assets: List<GalleryAssetResources>): CandidateSource =
    GalleryCandidateSource(inMemoryGallery(MutableStateFlow(assets)))

/** An admitting policy bounded below by [cutoff] — what the fake narrows its walk by. */
private suspend fun admitting(cutoff: String) = SelectionPolicy(
    selectionRulesFor(
        includesUpload = true,
        cutoff = CaptureCutoff(TimestampText.parse(cutoff)),
        ceiling = null,
        suppressedAssetIds = { emptySet() },
        albumExcludedAssetIds = { emptySet() },
    ),
)

/** The resources a source yields for [cutoff] — walk composed with the per-candidate mapping. */
private suspend fun CandidateSource.resourcesFor(cutoff: String) =
    readCandidates(admitting(cutoff)).flatMap { it.resources() }.map { it.filename }

/**
 * The candidates of a read this fake always answers: it reads a cell, so it is always `Readable`. Asserting that here
 * keeps every case below about the MAPPING rather than about a branch none of them exercises.
 */
private suspend fun CandidateSource.readCandidates(policy: SelectionPolicy): List<Candidate> =
    assertIs<CandidateRead.Readable>(candidates(policy), "a granted in-memory gallery always reads").candidates

/**
 * The gallery port's assets as the upload cycle's resources ([resourcesFrom]), over the in-memory library: role keys,
 * metadata, the facts every resource of an asset carries, and the walk composed with the per-candidate mapping.
 */
class GalleryResourceMappingTest {

    private fun resource(kind: ResourceKind, mime: String, name: String) = LibraryAssets.Resource(kind, mime, name)

    @Test
    fun maps_each_original_to_a_role_key_with_its_metadata() {
        val asset = LibraryAssets.photo(
            "ABC/L0/001",
            "2026-07-01T00:00:00Z",
            resources = listOf(
                resource(ResourceKind.PHOTO, "image/heic", "IMG_0001.HEIC"),
                resource(ResourceKind.PAIRED_VIDEO, "video/quicktime", "IMG_0001.MOV"),
            ),
        )

        val resources = resourcesFrom(listOf(asset))

        assertEquals(2, resources.size)
        val primary = resources.first { it.filename.endsWith("-primary.heic") }
        assertEquals(testAssetId("ABC_L0_001"), primary.assetId, "the app's id of the platform's")
        assertEquals("ABC_L0_001-primary.heic", primary.filename)
        // The resolved MIME, not a platform type identifier: `contentType` is what the upload provider sends as the
        // `Content-Type` header.
        assertEquals("image/heic", primary.contentType)
        assertEquals(primary.metadata[RESOURCE_META_MIME], primary.contentType, "one content type, not two")
        assertEquals("2026-07-01T00:00:00Z", primary.metadata[RESOURCE_META_CREATION_DATE])
        assertEquals("IMG_0001.HEIC", primary.metadata[RESOURCE_META_ORIGINAL_FILENAME])
        val live = resources.first { it.filename.endsWith("-live.mov") }
        assertEquals("ABC_L0_001-live.mov", live.filename)
        assertEquals("video/quicktime", live.metadata[RESOURCE_META_MIME])
    }

    @Test
    fun origin_facts_survive_the_mapping_onto_every_resource_of_the_asset() {
        val asset = LibraryAssets.photo(
            "ABC/L0/001",
            "2026-07-01T00:00:00Z",
            resources = listOf(
                resource(ResourceKind.PHOTO, "image/heic", "IMG_0001.HEIC"),
                resource(ResourceKind.PAIRED_VIDEO, "video/quicktime", "IMG_0001.MOV"),
            ),
            isScreenshot = true,
            isEdited = true,
            pixelWidth = 750,
            pixelHeight = 1334,
        )

        val resources = resourcesFrom(listOf(asset))

        // Every resource of the asset carries the asset's facts — the policy decides per-asset, so the paired video
        // must not be left without the facts that condemn (or save) its still.
        assertEquals(2, resources.size)
        for (r in resources) {
            assertEquals("true", r.metadata[RESOURCE_META_IS_SCREENSHOT])
            assertEquals("false", r.metadata[RESOURCE_META_IS_SCREEN_RECORDING])
            assertEquals("false", r.metadata[RESOURCE_META_IS_VIDEO])
            assertEquals("true", r.metadata[RESOURCE_META_IS_EDITED])
            assertEquals("${750L * 1334L}", r.metadata[RESOURCE_META_PIXEL_AREA])
        }
    }

    @Test
    fun the_walk_stays_decision_free_a_screenshot_is_mapped_not_dropped() = runTest {
        // The walk and the mapping carry facts; they never exclude. A screenshot must cross this seam intact — the
        // authoritative filter lives downstream in the upload cycle.
        val source = librarySource(listOf(LibraryAssets.screenshot("S1", "2026-07-01T00:00:00Z")))
        val policy = admitting("2026-01-01T00:00:00Z")

        val candidates = source.readCandidates(policy)
        assertEquals(1, candidates.size, "the walk emits the screenshot as a candidate — it does not drop it")
        assertTrue(EventPhotoSet(policy) { candidates }.assets().isEmpty(), "…and the policy is what excludes it")
    }

    @Test
    fun the_platform_handle_rides_into_resource_data_uninterpreted() {
        val resources = resourcesFrom(listOf(LibraryAssets.photo("A")))
        assertEquals(InMemoryResource(PlatformAssetId("A")), resources.single().data.galleryHandle())
    }

    @Test
    fun mapped_filename_round_trips_to_the_asset_id() {
        // The discovery -> key -> parse identity echo-suppression and reconstruct rely on.
        val resources =
            resourcesFrom(
                listOf(LibraryAssets.photo("ABC/L0/001", resources = listOf(LibraryAssets.primaryResource("x.JPG")))),
            )
        assertEquals(testAssetId("ABC_L0_001"), assetIdFromUploadKey(resources.single().filename))
    }

    @Test
    fun the_source_composes_walk_then_map_per_candidate() = runTest {
        val source = librarySource(
            listOf(
                LibraryAssets.photo("A", IN_SCOPE, resources = listOf(LibraryAssets.primaryResource("a.JPG"))),
                LibraryAssets.photo("B", IN_SCOPE, resources = listOf(LibraryAssets.primaryResource("b.JPG"))),
            ),
        )

        assertEquals(listOf("A-primary.jpg", "B-primary.jpg"), source.resourcesFor(CUTOFF))
    }

    @Test
    fun the_bounded_walk_excludes_assets_captured_before_the_bound() = runTest {
        // There is no unbounded walk: a membership always has a cutoff to scope it.
        val source = librarySource(
            listOf(
                LibraryAssets.photo(
                    "OLD",
                    "2000-01-01T00:00:00Z",
                    resources = listOf(LibraryAssets.primaryResource("old.JPG")),
                ),
                LibraryAssets.photo("NEW", IN_SCOPE, resources = listOf(LibraryAssets.primaryResource("new.JPG"))),
            ),
        )

        assertEquals(listOf("NEW-primary.jpg"), source.resourcesFor(CUTOFF))
    }

    @Test
    fun a_candidate_reads_its_resources_only_when_asked() = runTest {
        // The cost ladder, over the fake: obtaining candidates costs nothing per asset, and the mapping runs per
        // candidate when its resources are asked for.
        val source = librarySource(
            listOf(
                LibraryAssets.photo(
                    "OLD",
                    "2000-01-01T00:00:00Z",
                    resources = listOf(LibraryAssets.primaryResource("old.JPG")),
                ),
                LibraryAssets.photo("NEW", IN_SCOPE, resources = listOf(LibraryAssets.primaryResource("new.JPG"))),
            ),
        )

        val candidates = source.readCandidates(admitting(CUTOFF))
        assertEquals(
            listOf(testAssetId("NEW")),
            candidates.map { it.facts.assetId },
            "the walk is bounded by the floor",
        )
        assertEquals(listOf("NEW-primary.jpg"), candidates.single().resources().map { it.filename })
    }

    @Test
    fun an_undated_asset_is_before_every_bound() = runTest {
        // An asset the platform holds no capture date for is in no event window.
        val source =
            librarySource(
                listOf(LibraryAssets.photo("U", "", resources = listOf(LibraryAssets.primaryResource("u.JPG")))),
            )

        assertEquals(emptyList(), source.resourcesFor(CUTOFF))
    }
}

private const val CUTOFF = "2026-01-01T00:00:00Z"
private const val IN_SCOPE = "2026-06-01T10:00:00Z"
