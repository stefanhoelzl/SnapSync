package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** The bug report's reading of the composition's facts (capability `privacy-security`). */
class ReportFactsTest {

    private fun resource(asset: String, file: String) =
        Resource(
            filename = file,
            assetId = AssetId(asset),
            contentType = "image/jpeg",
            metadata = emptyMap(),
            data = Unit,
        )

    @Test
    fun `a resolved id is known and every reason there is none is failed`() {
        assertEquals(Fact.Known("ID-1"), DeviceIdResult.Id("ID-1", DeviceIdResult.Via.READ).asFact())
        assertEquals(
            Fact.Failed("the secure store could not be read: locked"),
            DeviceIdResult.Unavailable("locked").asFact(),
        )
        assertEquals(Fact.Failed("no device id stored yet"), DeviceIdResult.AbsentNotMintable.asFact())
    }

    @Test
    fun `a partial grant counts photos and not their resources`() {
        val livePhoto = listOf(resource("A", "a.heic"), resource("A", "a.mov"))
        val snapshot = livePhoto + resource("B", "b.jpg")

        assertEquals(Fact.Known(2), selectionPhotos(GalleryAccess.LIMITED, snapshot))
    }

    @Test
    fun `an unread selection is failed and never zero`() {
        assertEquals(Fact.Failed("the selection has not been read yet"), selectionPhotos(GalleryAccess.LIMITED, null))
        assertEquals(Fact.Known(0), selectionPhotos(GalleryAccess.LIMITED, emptyList()))
    }

    @Test
    fun `any grant but a partial one has no selection to count`() {
        assertEquals(Fact.Unsupported, selectionPhotos(GalleryAccess.GRANTED, null))
        assertEquals(Fact.Unsupported, selectionPhotos(GalleryAccess.DENIED, emptyList()))
    }

    @Test
    fun `a footprint is whole megabytes and an unread one unsupported`() {
        assertEquals(Fact.Known(3L), MemoryFootprint(footprintBytes = 3L * 1_048_576 + 5).inMegabytes())
        assertEquals(Fact.Unsupported, (null as MemoryFootprint?).inMegabytes())
    }

    @Test
    fun `a failed read fails every device fact with its reason`() {
        val failed = Fact.Failed("timed out")
        assertEquals(
            DeviceConditionsReading(failed, failed, failed, failed, failed, failed, failed),
            DeviceConditionsReading.failed("timed out"),
        )
    }
}
