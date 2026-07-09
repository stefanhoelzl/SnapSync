package app.snapsync.status

import app.snapsync.engine.Resource
import app.snapsync.gallery.InMemoryGalleryResourceEnumerator
import app.snapsync.gallery.RESOURCE_META_CREATION_DATE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/** Every membership carries a cutoff (capability `photo-date-cutoff`); there is no whole-library total. */
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
        // forever). NEW is at/after the cutoff → counted (capability photo-date-cutoff).
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
