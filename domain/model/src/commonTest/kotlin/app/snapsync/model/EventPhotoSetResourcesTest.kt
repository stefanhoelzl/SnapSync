package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * **The third rung of the cost ladder** (capability `photo-selection-policy`): `EventPhotoSet.resources()`
 * — the bytes to upload and the entries to list. `count()` and `assets()` are exercised in
 * `FactsOnlyAdmissionTest` and `CeilingReachesEveryConsumerTest`; this rung was reached by no test at all,
 * and it is the one whose output leaves the device.
 *
 * Two properties, and the class KDoc states both because getting either wrong is invisible:
 *
 * **Admission is per ASSET, not per resource.** "An asset's resources stand or fall together, or a Live
 * Photo's paired video survives its excluded primary as an orphan whose bytes nothing uploads." An
 * orphaned `.mov` is not a visible error anywhere — it is a file in someone's event that no photo claims.
 *
 * **The filter precedes the fetch.** Resources cost ~110 ms each on an SE2, so reading them for an asset
 * that will then be dropped is the shape this type was built to remove ("this filters first and fetches
 * only for what survives"). A lazy candidate whose `resources()` throws is how that becomes an assertion
 * rather than a comment.
 */
class EventPhotoSetResourcesTest {

    private val cutoff = captureCutoff("2026-06-01T00:00:00Z")
    private val ceiling = captureCeiling("2026-06-30T00:00:00Z")
    private val inWindow = "2026-06-15T12:00:00Z"

    private suspend fun policy(): SelectionPolicy = SelectionPolicy(
        selectionRulesFor(
            includesUpload = true,
            cutoff = cutoff,
            ceiling = ceiling,
            suppressedAssetIds = { emptySet() },
            albumExcludedAssetIds = { emptySet() },
        ),
    )

    private fun resource(
        assetId: String,
        filename: String,
        isScreenshot: Boolean = false,
        isVideo: Boolean = false,
        creationDate: String = inWindow,
        width: Long = 4032,
        height: Long = 3024,
    ) = Resource(
        filename = filename,
        assetId = assetId,
        contentType = if (isVideo) "public.mpeg-4" else "public.heic",
        metadata = mapOf(
            RESOURCE_META_CREATION_DATE to creationDate,
            RESOURCE_META_MIME to if (isVideo) "video/mp4" else "image/heic",
            RESOURCE_META_IS_SCREENSHOT to isScreenshot.toString(),
            RESOURCE_META_IS_VIDEO to isVideo.toString(),
            RESOURCE_META_IS_EDITED to "false",
            RESOURCE_META_PIXEL_AREA to (width * height).toString(),
        ),
        data = Unit,
    )

    private fun facts(
        assetId: String,
        isScreenshot: Boolean = false,
        creationDate: String = inWindow,
        width: Long = 4032,
        height: Long = 3024,
    ) = AssetFacts(
        assetId = assetId,
        creationDate = CaptureDate(creationDate),
        isScreenshot = isScreenshot,
        pixelArea = width * height,
    )

    @Test
    fun `an admitted asset contributes every one of its resources`() = runTest {
        // A Live Photo is one asset with two resources; both must ride, or the event holds a still whose
        // motion half never arrives.
        val live = listOf(
            resource("LIVE", "LIVE-primary.heic"),
            resource("LIVE", "LIVE-paired.mov", isVideo = true),
        )

        val out = EventPhotoSet(policy()) { candidatesFromResources(live) }.resources()

        assertEquals(listOf("LIVE-primary.heic", "LIVE-paired.mov"), out.map { it.filename })
    }

    @Test
    fun `an excluded asset contributes none of its resources rather than orphaning one`() = runTest {
        // The invariant the class KDoc names. A screenshot's resources stand or fall TOGETHER — a
        // per-resource filter would drop the primary and upload the paired file as an orphan that no
        // photo in the event claims and nothing reports.
        val library = listOf(
            resource("CAM", "CAM-primary.heic"),
            resource("SHOT", "SHOT-primary.heic", isScreenshot = true),
            resource("SHOT", "SHOT-paired.mov", isScreenshot = true, isVideo = true),
        )

        val out = EventPhotoSet(policy()) { candidatesFromResources(library) }.resources()

        assertEquals(listOf("CAM-primary.heic"), out.map { it.filename })
        assertTrue(out.none { it.assetId == "SHOT" }, "an excluded asset leaked a resource: $out")
    }

    @Test
    fun `an out-of-window asset contributes nothing on either side of the range`() = runTest {
        // The bounds are the capability's whole reason for existing: below the floor is someone else's
        // life before the event, above the ceiling is their life after it.
        val library = listOf(
            resource("BEFORE", "BEFORE.heic", creationDate = "2026-01-01T00:00:00Z"),
            resource("INSIDE", "INSIDE.heic"),
            resource("AFTER", "AFTER.heic", creationDate = "2026-07-15T12:00:00Z"),
        )

        val out = EventPhotoSet(policy()) { candidatesFromResources(library) }.resources()

        assertEquals(listOf("INSIDE.heic"), out.map { it.filename })
    }

    @Test
    fun `resources are fetched only for the assets that survive admission`() = runTest {
        // The cost ladder, as an assertion. ~110 ms per asset on an SE2 is what a fetch-then-filter shape
        // spends on photos it is about to drop — and this type exists to filter first.
        val fetched = mutableListOf<String>()
        val candidates = candidatesFromFacts(
            facts = listOf(facts("CAM"), facts("SHOT", isScreenshot = true)),
            resourcesFor = { assetId ->
                fetched += assetId
                listOf(resource(assetId, "$assetId.heic"))
            },
        )

        val out = EventPhotoSet(policy()) { candidates }.resources()

        assertEquals(listOf("CAM"), fetched, "a resource was read for an asset that was then dropped")
        assertEquals(listOf("CAM.heic"), out.map { it.filename })
    }

    @Test
    fun `a facts-only backing with no way to fetch resources yields none and does not fail`() = runTest {
        // The join preview's honest shape: it counts, it does not upload, so its default `resourcesFor`
        // returns nothing. That must be an empty answer rather than an error on a path that also serves
        // `count()`.
        val set = EventPhotoSet(policy()) { candidatesFromFacts(listOf(facts("CAM"))) }

        assertEquals(1, set.count())
        assertEquals(emptyList(), set.resources())
    }
}
