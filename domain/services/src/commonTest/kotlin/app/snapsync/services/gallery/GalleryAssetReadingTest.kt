package app.snapsync.services.gallery

import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.RESOURCE_META_MIME
import app.snapsync.model.RESOURCE_META_ORIGINAL_FILENAME
import app.snapsync.ports.ResourceKind
import app.snapsync.testids.testAssetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** How the app reads what the gallery port describes: upload resources. */
class GalleryAssetReadingTest {

    @Test
    fun `each original becomes an upload resource keyed by its role carrying its ledger row's facts and its handle`() {
        val live = platformPhoto(
            "L",
            resources = listOf(ResourceKind.PHOTO to "IMG.HEIC", ResourceKind.PAIRED_VIDEO to "IMG.MOV"),
        )
        val undated = platformPhoto("U", created = null)
        val resources = resourcesFrom(listOf(live, undated))
        assertEquals(listOf("L-primary.heic", "L-live.mov", "U-primary.jpg"), resources.map { it.filename })
        assertEquals(listOf(testAssetId("L"), testAssetId("L"), testAssetId("U")), resources.map { it.assetId })
        assertEquals("2026-06-01T10:00:00Z", resources[0].metadata[RESOURCE_META_CREATION_DATE])
        assertEquals("IMG.MOV", resources[1].metadata[RESOURCE_META_ORIGINAL_FILENAME])
        assertEquals("", resources[2].metadata[RESOURCE_META_CREATION_DATE], "an undated asset is carried undated")
        assertEquals(
            setOf(RESOURCE_META_CREATION_DATE, RESOURCE_META_ORIGINAL_FILENAME, RESOURCE_META_MIME),
            resources.flatMapTo(mutableSetOf()) { it.metadata.keys },
            "what the asset IS is not carried: the selection decided on the typed facts",
        )
        assertSame(TestHandle, resources[1].data.galleryHandle())
    }
}
