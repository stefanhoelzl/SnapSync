package app.snapsync.gallery

import app.snapsync.mock.LibraryAssets
import app.snapsync.mock.inMemoryGallery
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.CaptureDate
import app.snapsync.model.EventEnd
import app.snapsync.model.EventStart
import app.snapsync.model.GalleryAccess
import app.snapsync.model.GalleryRead
import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.model.ReceivedPhotoName
import app.snapsync.model.ResourceRole
import app.snapsync.model.SelectionScope
import app.snapsync.model.resourcesFrom
import app.snapsync.ports.GalleryReader
import app.snapsync.services.gallery.MarkedPhotoLookup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The join's read of the library's SnapSync-marked photos (capability `receiving-photos`), over the in-memory gallery. */
class MarkedPhotoLookupTest {

    private val start = EventStart(CaptureDate("2026-06-01T00:00:00Z"))
    private val end = EventEnd(CaptureDate("2026-06-10T00:00:00Z"))
    private val refA = AssetRef("dev-a", AssetId("SRC-A"))
    private val refB = AssetRef("dev-b", AssetId("SRC-B"))

    /** A received photo in the library: its primary (and, for a Live Photo, its video) named with [ref]'s mark. */
    private fun received(id: String, ref: AssetRef, date: String = "2026-06-05T12:00:00Z", live: Boolean = false): RawAsset =
        LibraryAssets.photo(
            id,
            creationDate = date,
            resources = listOfNotNull(
                LibraryAssets.primaryResource(ReceivedPhotoName.mark("IMG_1.HEIC", "k-primary.heic", ref), "image/heic"),
                if (live) RawResource(ResourceRole.LIVE, "video/quicktime", ReceivedPhotoName.mark("IMG_1.MOV", "k-live.mov", ref), Unit) else null,
            ),
        )

    private val library = listOf(
        received("L-A", refA, live = true),
        received("L-B", refB),
        LibraryAssets.photo("L-OWN", creationDate = "2026-06-05T12:00:00Z"), // the member's own capture: unmarked
        received("L-EARLY", AssetRef("dev-c", AssetId("SRC-C")), date = "2026-05-20T12:00:00Z"), // outside the event
    )

    /** The gallery's reads, recording every id a resource read asked for. */
    private class Recording(private val reader: GalleryReader) : GalleryReader by reader {
        val resourceReads = mutableListOf<Set<AssetId>>()
        override suspend fun resources(ids: Set<AssetId>): GalleryRead<List<RawAsset>> =
            reader.resources(ids).also { resourceReads += ids }
    }

    @Test
    fun a_full_grant_finds_the_marked_photos_in_the_event_window() = runTest {
        val gallery = Recording(inMemoryGallery(MutableStateFlow(library)))
        val found = MarkedPhotoLookup(gallery) { SelectionScope.Unrestricted }.markedIn(start, end, known = emptySet())
        assertEquals(
            mapOf(ReceivedPhotoName.token(refA) to AssetId("L-A"), ReceivedPhotoName.token(refB) to AssetId("L-B")),
            found,
        )
        assertTrue(AssetId("L-EARLY") !in gallery.resourceReads.flatten(), "a photo outside the event is never read")
    }

    @Test
    fun a_known_asset_is_never_read() = runTest {
        val gallery = Recording(inMemoryGallery(MutableStateFlow(library)))
        val found = MarkedPhotoLookup(gallery) { SelectionScope.Unrestricted }.markedIn(start, end, known = setOf(AssetId("L-A")))
        assertEquals(mapOf(ReceivedPhotoName.token(refB) to AssetId("L-B")), found)
        assertTrue(AssetId("L-A") !in gallery.resourceReads.flatten())
    }

    @Test
    fun a_partial_grant_reads_the_snapshot_and_not_the_library() = runTest {
        val gallery = Recording(inMemoryGallery(MutableStateFlow(library), MutableStateFlow(GalleryAccess.LIMITED)))
        val selection = resourcesFrom(library.filter { it.assetId.value != "L-B" }) // L-B was not selected
        val found = MarkedPhotoLookup(gallery) { SelectionScope.Scoped(selection) }.markedIn(start, end, known = emptySet())
        assertEquals(mapOf(ReceivedPhotoName.token(refA) to AssetId("L-A")), found)
        assertTrue(gallery.resourceReads.isEmpty(), "the selection already carries every name")
    }

    @Test
    fun an_unread_selection_or_no_grant_finds_nothing() = runTest {
        val gallery = inMemoryGallery(MutableStateFlow(library), MutableStateFlow(GalleryAccess.DENIED))
        assertEquals(emptyMap(), MarkedPhotoLookup(gallery) { SelectionScope.Unread }.markedIn(start, end, emptySet()))
        assertEquals(emptyMap(), MarkedPhotoLookup(gallery) { SelectionScope.Unrestricted }.markedIn(start, end, emptySet()))
    }
}
