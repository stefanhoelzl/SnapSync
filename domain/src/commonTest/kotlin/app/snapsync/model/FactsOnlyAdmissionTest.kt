package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * **A cheap admission and an expensive one give the same answer** (capability `photo-selection-policy`).
 *
 * This is the property removing the animated-image rule buys, and it is worth a test of its own because
 * its absence was a live divergence rather than a theoretical one:
 *
 * - the join preview walks **facts-only** (no `assetResourcesForAsset`), so it could not read a resource's
 *   MIME and admitted a GIF on doubt;
 * - the own-device total `N` walks **eagerly**, so it read the MIME and excluded one.
 *
 * Both call themselves "the admitted set". For any library holding an in-scope GIF the preview
 * over-counted — the same shape as the capture-date ceiling bug, reached through a different door.
 *
 * With every rule decidable on facts, the two paths are the same computation over the same inputs, so the
 * test asserts they agree over a library deliberately containing one of everything the policy has an
 * opinion about.
 */
class FactsOnlyAdmissionTest {

    private val cutoff = captureCutoff("2026-06-01T00:00:00Z")
    private val ceiling = captureCeiling("2026-06-30T00:00:00Z")
    private val inWindow = "2026-06-15T12:00:00Z"

    /** The membership's policyOf(). A `suspend fun` rather than a `val`: the one derivation reads two ports. */
    private suspend fun policyOf(ceiling: CaptureCeiling? = this.ceiling): SelectionPolicy = SelectionPolicy(
        selectionRulesFor(
            includesUpload = true,
            cutoff = cutoff,
            ceiling = ceiling,
            suppressedAssetIds = { emptySet() },
            albumExcludedAssetIds = { emptySet() },
        ),
    )

    /** One asset with its resources present — what the eager walk produces. */
    private fun withResources(
        assetId: String,
        creationDate: String = inWindow,
        isScreenshot: Boolean = false,
        width: Long = 4032,
        height: Long = 3024,
        mime: String = "image/heic",
    ) = Resource(
        filename = "$assetId-primary.heic",
        assetId = assetId,
        contentType = "public.heic",
        metadata = mapOf(
            RESOURCE_META_CREATION_DATE to creationDate,
            RESOURCE_META_MIME to mime,
            RESOURCE_META_IS_SCREENSHOT to isScreenshot.toString(),
            RESOURCE_META_IS_VIDEO to "false",
            RESOURCE_META_IS_EDITED to "false",
            RESOURCE_META_PIXEL_AREA to (width * height).toString(),
        ),
        data = Unit,
    )

    /** The same asset as the facts-only walk produces it — no resources, so no MIME in reach. */
    private fun factsOnly(
        assetId: String,
        creationDate: String = inWindow,
        isScreenshot: Boolean = false,
        width: Long = 4032,
        height: Long = 3024,
    ) = AssetFacts(
        assetId = assetId,
        creationDate = CaptureDate(creationDate),
        isScreenshot = isScreenshot,
        pixelArea = width * height,
    )

    @Test
    fun `the facts-only and resource-carrying admissions agree`() = runTest {
        // One of everything the policy has an opinion about, including a GIF — the asset that used to
        // divide the two paths.
        val eager = listOf(
            withResources("CAM"),
            withResources("SHOT", isScreenshot = true),
            withResources("SMALL", width = 1600, height = 1200),
            withResources("GIF", width = 480, height = 270, mime = "image/gif"),
            withResources("OLD", creationDate = "2026-01-01T00:00:00Z"),
            withResources("AFTER", creationDate = "2026-07-15T12:00:00Z"),
        )
        val cheap = listOf(
            factsOnly("CAM"),
            factsOnly("SHOT", isScreenshot = true),
            factsOnly("SMALL", width = 1600, height = 1200),
            factsOnly("GIF", width = 480, height = 270), // no MIME available — and none needed
            factsOnly("OLD", creationDate = "2026-01-01T00:00:00Z"),
            factsOnly("AFTER", creationDate = "2026-07-15T12:00:00Z"),
        )

        val fromEager = EventPhotoSet(policyOf()) { candidatesFromResources(eager) }
            .assets().mapTo(mutableSetOf()) { it.facts.assetId }
        val fromCheap = EventPhotoSet(policyOf()) { candidatesFromFacts(cheap) }
            .assets().mapTo(mutableSetOf()) { it.facts.assetId }

        assertEquals(setOf("CAM"), fromEager)
        assertEquals(fromEager, fromCheap, "the cheap path is the exact answer, not an approximation")
    }

    @Test
    fun `the counts agree too`() = runTest {
        // Stated separately because a count is what the two divergent consumers actually report.
        val gif = withResources("GIF", width = 480, height = 270, mime = "image/gif")
        val cam = withResources("CAM")

        val eagerCount = EventPhotoSet(policyOf()) { candidatesFromResources(listOf(cam, gif)) }.count()
        val cheapCount = EventPhotoSet(policyOf()) {
            candidatesFromFacts(listOf(factsOnly("CAM"), factsOnly("GIF", width = 480, height = 270)))
        }.count()

        assertEquals(1, eagerCount)
        assertEquals(eagerCount, cheapCount)
    }

    @Test
    fun `no rule reads a resource`() = runTest {
        // The structural version of the property: an admission over candidates whose resources() would
        // THROW still resolves, because nothing calls it to decide.
        val exploding = listOf(
            object : Candidate {
                override val facts = factsOnly("CAM")
                override suspend fun resources(): List<Resource> =
                    error("admission must not read resources")
            },
            object : Candidate {
                override val facts = factsOnly("SHOT", isScreenshot = true)
                override suspend fun resources(): List<Resource> =
                    error("admission must not read resources")
            },
        )

        assertEquals(1, EventPhotoSet(policyOf()) { exploding }.count())
    }
}
