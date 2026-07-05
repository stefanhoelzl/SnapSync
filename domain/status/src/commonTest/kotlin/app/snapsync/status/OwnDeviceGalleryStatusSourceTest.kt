package app.snapsync.status

import app.snapsync.engine.Resource
import app.snapsync.gallery.InMemoryGalleryResourceEnumerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class OwnDeviceGalleryStatusSourceTest {

    private fun resource(filename: String, assetId: String) =
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

        source.refresh()

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

        source.refresh()

        assertEquals(1, source.size.value, "total counts only own assets (A), not the downloaded B")
    }

    @Test
    fun `refresh recomputes after the library changes`() = runTest {
        val enumerator = InMemoryGalleryResourceEnumerator(listOf(resource("A-primary.jpg", "A")))
        val source = OwnDeviceGalleryStatusSource(enumerator)
        source.refresh()
        assertEquals(1, source.size.value)

        enumerator.set(listOf(resource("A-primary.jpg", "A"), resource("C-primary.jpg", "C")))
        source.refresh()
        assertEquals(2, source.size.value)
    }
}
