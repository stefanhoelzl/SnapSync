package app.snapsync.status

import app.snapsync.engine.Resource
import app.snapsync.gallery.InMemoryGalleryResourceEnumerator
import app.snapsync.gallery.MEDIA_TYPE_IMAGE
import app.snapsync.gallery.RESOURCE_META_CREATION_DATE
import app.snapsync.gallery.RESOURCE_META_MEDIA_SUBTYPES
import app.snapsync.gallery.RESOURCE_META_MEDIA_TYPE
import app.snapsync.gallery.RESOURCE_META_PIXEL_HEIGHT
import app.snapsync.gallery.RESOURCE_META_PIXEL_WIDTH
import app.snapsync.gallery.SUBTYPE_NONE
import app.snapsync.gallery.SUBTYPE_SCREENSHOT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/** Every membership carries a cutoff (capability `photo-selection-policy`); there is no whole-library total. */
private const val CUTOFF = "2026-07-06T00:00:00Z"

/** After [CUTOFF], so a default-dated resource is in scope. */
private const val IN_SCOPE = "2026-07-10T00:00:00Z"

class OwnDeviceGalleryStatusSourceTest {

    /** Dated in scope by default: an asset with no `creationDate` is out of scope under any cutoff. */
    private fun resource(filename: String, assetId: String) =
        Resource(filename, assetId, "image/jpeg", mapOf(RESOURCE_META_CREATION_DATE to IN_SCOPE), Unit)

    private fun datedResource(filename: String, assetId: String, creationDate: String) =
        Resource(filename, assetId, "image/jpeg", mapOf(RESOURCE_META_CREATION_DATE to creationDate), Unit)

    private fun undatedResource(filename: String, assetId: String) =
        Resource(filename, assetId, "image/jpeg", emptyMap(), Unit)

    /** A resource carrying the origin facts (capability `photo-selection-policy`). */
    private fun originResource(
        filename: String,
        assetId: String,
        subtypes: Long = SUBTYPE_NONE,
        width: Long = 4032,
        height: Long = 3024,
    ) = Resource(
        filename, assetId, "public.heic",
        mapOf(
            RESOURCE_META_CREATION_DATE to IN_SCOPE,
            RESOURCE_META_MEDIA_SUBTYPES to subtypes.toString(),
            RESOURCE_META_MEDIA_TYPE to MEDIA_TYPE_IMAGE.toString(),
            RESOURCE_META_PIXEL_WIDTH to width.toString(),
            RESOURCE_META_PIXEL_HEIGHT to height.toString(),
        ),
        Unit,
    )

    @Test
    fun `an origin-excluded asset does not inflate the total`() = runTest {
        // The status source enumerates INDEPENDENTLY of the upload cycle, so it must apply the identical
        // policy. If it counted the screenshot the cycle refuses to upload, N would be 3 while only 2 could
        // ever complete — and the joined screen would sit at "pending" forever. That is the whole reason
        // this rule is a requirement rather than an implementation detail.
        val enumerator = InMemoryGalleryResourceEnumerator(
            listOf(
                originResource("cam-primary.heic", "CAM"),
                originResource("shot-primary.png", "SHOT", subtypes = SUBTYPE_SCREENSHOT),
                originResource("wa-primary.jpg", "WA", width = 1600, height = 1200), // 1.9 MP → below floor
            ),
        )
        val source = OwnDeviceGalleryStatusSource(enumerator)

        source.refresh(CUTOFF)

        assertEquals(1, source.size.value, "only the camera photo counts toward N")
    }

    @Test
    fun `a denylisted album member does not inflate the total`() = runTest {
        val enumerator = InMemoryGalleryResourceEnumerator(
            listOf(originResource("cam.heic", "CAM"), originResource("wa.heic", "WA")),
        )
        val source = OwnDeviceGalleryStatusSource(enumerator, albumExcludedAssetIds = { setOf("WA") })

        source.refresh(CUTOFF)

        assertEquals(1, source.size.value)
    }

    @Test
    fun `size counts own qualifying assets by photo`() = runTest {
        val enumerator = InMemoryGalleryResourceEnumerator(
            listOf(
                resource("A-primary.jpg", "A"),
                resource("A-live.mov", "A"), // A is a Live Photo: two resources, one photo
                resource("B-primary.jpg", "B"),
            ),
        )
        val source = OwnDeviceGalleryStatusSource(enumerator)

        source.refresh(CUTOFF)

        assertEquals(2, source.size.value) // A and B — counted by photo, not resource row
    }

    @Test
    fun `downloaded suppressed assets are excluded from the upload total`() = runTest {
        // B is a foreign photo this device downloaded + imported (suppressed). It is in the library
        // (enumerated) but must NOT count toward the upload universe — else progress pegs below 100%.
        val enumerator = InMemoryGalleryResourceEnumerator(
            listOf(
                resource("A-primary.jpg", "A"), // own
                resource("B-primary.jpg", "B"), // downloaded foreign (suppressed)
            ),
        )
        val source = OwnDeviceGalleryStatusSource(enumerator, suppressedLocalIds = { setOf("B") })

        source.refresh(CUTOFF)

        assertEquals(1, source.size.value, "total counts only own assets (A), not the downloaded B")
    }

    @Test
    fun `refresh recomputes after the library changes`() = runTest {
        val enumerator = InMemoryGalleryResourceEnumerator(listOf(resource("A-primary.jpg", "A")))
        val source = OwnDeviceGalleryStatusSource(enumerator)
        source.refresh(CUTOFF)
        assertEquals(1, source.size.value)

        enumerator.set(listOf(resource("A-primary.jpg", "A"), resource("C-primary.jpg", "C")))
        source.refresh(CUTOFF)
        assertEquals(2, source.size.value)
    }

    @Test
    fun `pre-cutoff assets are excluded from the total so progress can reach 100 percent`() = runTest {
        // OLD precedes the cutoff → never uploads → must not inflate N (else the screen shows "pending"
        // forever). NEW is at/after the cutoff → counted (capability photo-selection-policy).
        val enumerator = InMemoryGalleryResourceEnumerator(
            listOf(
                datedResource("OLD-primary.jpg", "OLD", "2026-07-01T00:00:00Z"),
                datedResource("NEW-primary.jpg", "NEW", "2026-07-10T00:00:00Z"),
            ),
        )
        val source = OwnDeviceGalleryStatusSource(enumerator)

        source.refresh(CUTOFF)

        assertEquals(1, source.size.value, "only the post-cutoff asset (NEW) counts toward the total")
    }

    @Test
    fun `an undated asset is excluded under a cutoff`() = runTest {
        val enumerator = InMemoryGalleryResourceEnumerator(listOf(undatedResource("U-primary.jpg", "U")))
        val source = OwnDeviceGalleryStatusSource(enumerator)

        source.refresh(CUTOFF)

        assertEquals(0, source.size.value, "an asset with no creationDate is out of scope under a cutoff")
    }
}
