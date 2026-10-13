package app.snapsync.services.gallery

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.model.ImportResult
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.RESOURCE_META_PIXEL_AREA
import app.snapsync.model.ResourceRole
import app.snapsync.model.TimestampText
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.ImportOutcome
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ResourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** How the app reads what the gallery port describes: facts, upload resources, and an import's outcome. */
class GalleryAssetReadingTest {

    @Test
    fun `an asset's facts are the platform's under the app's id and an undated one carries no date`() {
        val dated = platformPhoto("x/L0/001", created = "2026-06-01T10:00:00.750Z").asset.facts()
        assertEquals(AssetId("x_L0_001"), dated.assetId)
        assertEquals(
            CaptureDate.Dated(TimestampText.parse("2026-06-01T10:00:00Z")),
            dated.captureDate,
            "to the second, as every date compared",
        )
        assertEquals(4032L * 3024L, dated.pixelArea)
        assertEquals(CaptureDate.Undated, platformPhoto("u", created = null).asset.facts().captureDate)
    }

    @Test
    fun `a still and a video are an asset's primary and paired motion its live resource`() {
        assertEquals(ResourceRole.PRIMARY, ResourceKind.PHOTO.role())
        assertEquals(ResourceRole.PRIMARY, ResourceKind.VIDEO.role())
        assertEquals(ResourceRole.LIVE, ResourceKind.PAIRED_VIDEO.role())
    }

    @Test
    fun `each original becomes an upload resource keyed by its role carrying its asset's facts and its handle`() {
        val live = platformPhoto(
            "L",
            resources = listOf(ResourceKind.PHOTO to "IMG.HEIC", ResourceKind.PAIRED_VIDEO to "IMG.MOV"),
        )
        val unknownArea = GalleryAssetResources(
            GalleryAsset(PlatformAssetId("U"), null, false, false, false, false, pixelArea = null),
            platformPhoto("U").resources,
        )
        val resources = resourcesFrom(listOf(live, unknownArea))
        assertEquals(listOf("L-primary.heic", "L-live.mov", "U-primary.jpg"), resources.map { it.filename })
        assertEquals("2026-06-01T10:00:00Z", resources[0].metadata[RESOURCE_META_CREATION_DATE])
        assertEquals("", resources[2].metadata[RESOURCE_META_PIXEL_AREA], "an unknown area is carried as unknown")
        assertSame(TestHandle, resources[1].data.galleryHandle())
    }

    @Test
    fun `an import's outcome reads with its assets under their app ids`() {
        assertEquals(
            ImportResult.Imported(AssetId("a_L0_001")),
            ImportOutcome.Imported(PlatformAssetId("a/L0/001")).importResult(),
        )
        assertEquals(
            ImportResult.Failed("refused", consumedResources = true, placeholder = AssetId("p")),
            ImportOutcome.Failed(
                "refused",
                consumedResources = true,
                placeholder = PlatformAssetId("p"),
            ).importResult(),
        )
        assertEquals(ImportResult.Failed("refused"), ImportOutcome.Failed("refused").importResult())
    }
}
