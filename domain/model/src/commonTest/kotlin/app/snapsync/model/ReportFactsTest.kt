package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** The bug report's reading of the composition's facts (capability `privacy-security`). */
class ReportFactsTest {

    @Test
    fun `a partial grant counts the selected photos`() {
        assertEquals(Fact.Known(2), selectionPhotos(GalleryAccess.LIMITED, 2))
    }

    @Test
    fun `an unread selection is failed and never zero`() {
        assertEquals(Fact.Failed("the selection has not been read yet"), selectionPhotos(GalleryAccess.LIMITED, null))
        assertEquals(Fact.Known(0), selectionPhotos(GalleryAccess.LIMITED, 0))
    }

    @Test
    fun `any grant but a partial one has no selection to count`() {
        assertEquals(Fact.Unsupported, selectionPhotos(GalleryAccess.GRANTED, null))
        assertEquals(Fact.Unsupported, selectionPhotos(GalleryAccess.DENIED, 0))
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
