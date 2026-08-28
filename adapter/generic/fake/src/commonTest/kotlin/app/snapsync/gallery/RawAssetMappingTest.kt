package app.snapsync.gallery

import app.snapsync.fake.InMemoryCandidateSource
import app.snapsync.model.AssetFacts
import app.snapsync.model.Candidate
import app.snapsync.model.CandidateRead
import app.snapsync.model.CaptureDate
import app.snapsync.model.RESOURCE_META_IS_EDITED
import app.snapsync.model.RESOURCE_META_IS_SCREENSHOT
import app.snapsync.model.RESOURCE_META_IS_SCREEN_RECORDING
import app.snapsync.model.RESOURCE_META_IS_VIDEO
import app.snapsync.model.RESOURCE_META_PIXEL_AREA
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.ResourceRole
import app.snapsync.model.RESOURCE_META_MIME
import app.snapsync.model.RESOURCE_META_ORIGINAL_FILENAME
import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.selectionRulesFor
import app.snapsync.model.EventPhotoSet
import app.snapsync.model.candidatesFromResources
import app.snapsync.model.captureCutoff
import app.snapsync.model.resourcesFrom

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The fan-out orchestration ([resourcesFrom]) exercised off-device — the coverage Move A unlocks. This
 * loop (role filter, `'/'→'_'` normalization, `uploadKey`, metadata assembly) previously lived only in
 * the iOS enumerator; here it runs on JVM and the iOS simulator against a fake raw-asset walk.
 */
/** An admitting policy bounded below by [cutoff] — what the fake narrows its walk by. */
private suspend fun admitting(cutoff: String) =
    SelectionPolicy(selectionRulesFor(includesUpload = true, cutoff = captureCutoff(cutoff), ceiling = null, suppressedAssetIds = { emptySet() }, albumExcludedAssetIds = { emptySet() }))

/** The resources a source yields for [cutoff] — walk composed with the per-candidate mapping. */
private suspend fun InMemoryCandidateSource.resourcesFor(cutoff: String) =
    readCandidates(admitting(cutoff)).flatMap { it.resources() }.map { it.filename }

/**
 * The candidates of a read this fake always answers: it reads a cell, so it is always `Readable`
 * (capability `gallery-status`). Asserting that here keeps every case below about the MAPPING rather
 * than about a branch none of them exercises.
 */
private suspend fun InMemoryCandidateSource.readCandidates(policy: SelectionPolicy): List<Candidate> =
    assertIs<CandidateRead.Readable>(candidates(policy), "the in-memory source always reads").candidates

class RawAssetMappingTest {

    // `role = null` is what the platform adapter reports for a resource it carries no role for
    // (an edit artifact, a RAW alternate, a proxy) — the drop decision is made platform-side now,
    // so this helper takes the neutral answer rather than a PHAssetResourceType integer.
    private fun raw(
        role: ResourceRole?,
        mime: String = "image/jpeg",
        name: String = "IMG.JPG",
        handle: Any = Unit,
    ) = RawResource(role = role, mimeContentType = mime, originalFilename = name, handle = handle)

    @Test
    fun maps_originals_only_with_role_keys_normalization_and_metadata() {
        val asset = RawAsset(
            assetId = "ABC/L0/001", // raw localIdentifier with '/'
            creationDate = "2026-07-01T00:00:00Z",
            rawResources = listOf(
                raw(ResourceRole.PRIMARY, mime = "image/heic", name = "IMG_0001.HEIC"), // photo -> primary
                raw(ResourceRole.LIVE, mime = "video/quicktime", name = "IMG_0001.MOV"), // pairedVideo -> live
                raw(null), // fullSizePhoto -> dropped (edit artifact)
                raw(null), // adjustmentData -> dropped
            ),
        )

        val resources = resourcesFrom(listOf(asset))

        assertEquals(2, resources.size, "only the two originals survive the role filter")
        val primary = resources.first { it.filename.endsWith("-primary.heic") }
        assertEquals("ABC_L0_001", primary.assetId, "assetId normalized '/'->'_'")
        assertEquals("ABC_L0_001-primary.heic", primary.filename)
        // The resolved MIME, not a platform type identifier: `contentType` is what the upload
        // provider sends as the `Content-Type` header, and it used to carry a UTI while the correct
        // MIME sat unused in metadata (spec `gallery-status`).
        assertEquals("image/heic", primary.contentType)
        assertEquals(primary.metadata[RESOURCE_META_MIME], primary.contentType, "one content type, not two")
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
                raw(ResourceRole.PRIMARY, mime = "image/heic", name = "IMG_0001.HEIC"),
                raw(ResourceRole.LIVE, mime = "video/quicktime", name = "IMG_0001.MOV"),
            ),
            facts = AssetFacts(
                assetId = "ABC/L0/001",
                creationDate = CaptureDate("2026-07-01T00:00:00Z"),
                isScreenshot = true,
                isEdited = true,
                pixelArea = 750L * 1334L,
            ),
        )

        val resources = resourcesFrom(listOf(asset))

        // Every resource of the asset carries the asset's facts — the policy decides per-asset, so the
        // paired video must not be left without the facts that condemn (or save) its primary.
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
        // The walk and the mapping carry facts; they never exclude. A screenshot must cross this seam intact
        // — the authoritative filter lives downstream in the upload cycle, and putting it here instead would
        // hide it from the upload cycle's tests (`:domain` feature/upload) and from the status total.
        val screenshot = RawAsset(
            assetId = "S1",
            creationDate = "2026-07-01T00:00:00Z",
            rawResources = listOf(raw(ResourceRole.PRIMARY, mime = "image/png", name = "IMG_0002.PNG")),
            facts = AssetFacts(
                assetId = "S1",
                creationDate = CaptureDate("2026-07-01T00:00:00Z"),
                isScreenshot = true,
                pixelArea = 750L * 1334L,
            ),
        )
        val source = InMemoryCandidateSource(listOf(screenshot))
        val policy = admitting("2026-01-01T00:00:00Z")

        val candidates = source.readCandidates(policy)
        assertEquals(1, candidates.size, "the walk emits the screenshot as a candidate — it does not drop it")
        assertTrue(EventPhotoSet(policy) { candidates }.assets().isEmpty(), "…and the policy is what excludes it")
    }

    @Test
    fun opaque_handle_rides_into_resource_data_uninterpreted() {
        val marker = Any()
        val resources = resourcesFrom(listOf(RawAsset("A", "", listOf(raw(ResourceRole.PRIMARY, handle = marker)))))
        assertEquals(marker, resources.single().data, "the PHAssetResource handle crosses uninterpreted")
    }

    @Test
    fun mapped_filename_round_trips_to_the_normalized_assetid() {
        // The discovery->key->parse identity echo-suppression + reconstruct rely on (change 1's parser).
        val resources = resourcesFrom(listOf(RawAsset("ABC/L0/001", "", listOf(raw(ResourceRole.PRIMARY, name = "x.JPG")))))
        assertEquals("ABC_L0_001", assetIdFromUploadKey(resources.single().filename))
    }

    @Test
    fun the_source_composes_walk_then_map_per_candidate() = runTest {
        val source = InMemoryCandidateSource(
            listOf(
                RawAsset("A", IN_SCOPE, listOf(raw(ResourceRole.PRIMARY, name = "a.JPG"))),
                RawAsset("B", IN_SCOPE, listOf(raw(ResourceRole.PRIMARY, name = "b.JPG"))),
            ),
        )

        assertEquals(listOf("A-primary.jpg", "B-primary.jpg"), source.resourcesFor(CUTOFF))
    }

    @Test
    fun the_bounded_walk_excludes_assets_captured_before_the_bound() = runTest {
        // There is no unbounded walk (capability `photo-selection-policy`): the whole-library enumeration cost
        // one synchronous PhotoKit round-trip per asset, and a membership always has a cutoff to scope it.
        val source = InMemoryCandidateSource(
            listOf(
                RawAsset("OLD", "2000-01-01T00:00:00Z", listOf(raw(ResourceRole.PRIMARY, name = "old.JPG"))),
                RawAsset("NEW", IN_SCOPE, listOf(raw(ResourceRole.PRIMARY, name = "new.JPG"))),
            ),
        )

        assertEquals(listOf("NEW-primary.jpg"), source.resourcesFor(CUTOFF))
    }

    @Test
    fun a_candidate_reads_its_resources_only_when_asked() = runTest {
        // The cost ladder, over the fake: obtaining candidates costs nothing per asset, and the mapping
        // (role filter, upload key, id normalization) runs per candidate when its resources are asked for.
        // The id-scoped incremental walk that used to be tested here is now internal to `IosDiscovery` —
        // only it has identifiers to scope by, because only it reads the change feed.
        val source = InMemoryCandidateSource(
            listOf(
                RawAsset("OLD", "2000-01-01T00:00:00Z", listOf(raw(ResourceRole.PRIMARY, name = "old.JPG"))),
                RawAsset("NEW", IN_SCOPE, listOf(raw(ResourceRole.PRIMARY, name = "new.JPG"))),
            ),
        )

        val candidates = source.readCandidates(admitting(CUTOFF))
        assertEquals(listOf("NEW"), candidates.map { it.facts.assetId }, "the walk is bounded by the floor")
        assertEquals(listOf("NEW-primary.jpg"), candidates.single().resources().map { it.filename })
    }

    @Test
    fun an_undated_asset_is_before_every_bound() = runTest {
        // An empty `creationDate` sorts before any non-empty cutoff, so an undated asset is never in scope.
        val source = InMemoryCandidateSource(listOf(RawAsset("U", "", listOf(raw(ResourceRole.PRIMARY, name = "u.JPG")))))

        assertEquals(emptyList(), source.resourcesFor(CUTOFF))
    }
}

private const val CUTOFF = "2026-01-01T00:00:00Z"
private const val IN_SCOPE = "2026-06-01T10:00:00Z"
