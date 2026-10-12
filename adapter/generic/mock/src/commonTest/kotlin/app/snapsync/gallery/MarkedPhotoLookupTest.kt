package app.snapsync.gallery

import app.snapsync.mock.LibraryAssets
import app.snapsync.mock.id
import app.snapsync.mock.inMemoryGallery
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.CaptureDate
import app.snapsync.model.EventEnd
import app.snapsync.model.EventStart
import app.snapsync.model.GalleryRead
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.ReceivedPhotoName
import app.snapsync.model.Resource
import app.snapsync.model.SelectionScope
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ResourceKind
import app.snapsync.services.gallery.MarkedPhotoLookup
import app.snapsync.services.gallery.resourcesFrom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The join's read of the library's SnapSync-marked photos (capability `receiving-photos`), over the in-memory gallery. */
class MarkedPhotoLookupTest {

    private val start = EventStart(CaptureDate("2026-06-01T00:00:00Z"))
    private val end = EventEnd(CaptureDate("2026-06-10T00:00:00Z"))
    private val refA = AssetRef("dev-a", AssetId("SRC-A"))
    private val refB = AssetRef("dev-b", AssetId("SRC-B"))

    /** A received photo in the library: its primary (and, for a Live Photo, its video) named with [ref]'s mark. */
    private fun received(
        id: String,
        ref: AssetRef,
        date: String = "2026-06-05T12:00:00Z",
        live: Boolean = false,
    ): GalleryAssetResources =
        LibraryAssets.photo(
            id,
            creationDate = date,
            resources = listOfNotNull(
                LibraryAssets.primaryResource(
                    ReceivedPhotoName.mark("IMG_1.HEIC", "k-primary.heic", ref),
                    "image/heic",
                ),
                if (live) {
                    LibraryAssets.Resource(
                        ResourceKind.PAIRED_VIDEO,
                        "video/quicktime",
                        ReceivedPhotoName.mark("IMG_1.MOV", "k-live.mov", ref),
                    )
                } else {
                    null
                },
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
        val resourceReads = mutableListOf<Set<PlatformAssetId>>()
        override suspend fun resources(ids: Set<PlatformAssetId>): GalleryRead<List<GalleryAssetResources>> =
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
        assertTrue(
            PlatformAssetId("L-EARLY") !in gallery.resourceReads.flatten(),
            "a photo outside the event is never read",
        )
    }

    @Test
    fun a_known_asset_is_never_read() = runTest {
        val gallery = Recording(inMemoryGallery(MutableStateFlow(library)))
        val found = MarkedPhotoLookup(
            gallery,
        ) { SelectionScope.Unrestricted }.markedIn(start, end, known = setOf(AssetId("L-A")))
        assertEquals(mapOf(ReceivedPhotoName.token(refB) to AssetId("L-B")), found)
        assertTrue(PlatformAssetId("L-A") !in gallery.resourceReads.flatten())
    }

    @Test
    fun a_partial_grant_reads_the_snapshot_and_not_the_library() = runTest {
        val gallery = Recording(inMemoryGallery(MutableStateFlow(library), MutableStateFlow(LibraryGrant.LIMITED)))
        val selection = resourcesFrom(library.filter { it.id.value != "L-B" }) // L-B was not selected
        val found = MarkedPhotoLookup(
            gallery,
        ) { SelectionScope.Scoped(selection) }.markedIn(start, end, known = emptySet())
        assertEquals(mapOf(ReceivedPhotoName.token(refA) to AssetId("L-A")), found)
        assertTrue(gallery.resourceReads.isEmpty(), "the selection already carries every name")
    }

    @Test
    fun an_unread_selection_finds_nothing_and_no_grant_reads_nothing() = runTest {
        val gallery = inMemoryGallery(MutableStateFlow(library), MutableStateFlow(LibraryGrant.DENIED))
        assertEquals(emptyMap(), MarkedPhotoLookup(gallery) { SelectionScope.Unread }.markedIn(start, end, emptySet()))
        assertNull(
            MarkedPhotoLookup(gallery) { SelectionScope.Unrestricted }.markedIn(start, end, emptySet()),
            "unreadable",
        )
    }

    @Test
    fun a_known_asset_in_the_selection_is_passed_over_and_a_primary_with_no_name_is_unmarked() = runTest {
        val gallery = inMemoryGallery(MutableStateFlow(library), MutableStateFlow(LibraryGrant.LIMITED))
        val nameless = Resource(
            "L-X-primary.heic",
            AssetId("L-X"),
            "image/heic",
            mapOf(RESOURCE_META_CREATION_DATE to "2026-06-05T12:00:00Z"),
            Unit,
        )
        val found = MarkedPhotoLookup(gallery) { SelectionScope.Scoped(resourcesFrom(library) + nameless) }
            .markedIn(start, end, known = setOf(AssetId("L-A")))
        assertEquals(mapOf(ReceivedPhotoName.token(refB) to AssetId("L-B")), found)
    }

    @Test
    fun a_library_whose_resources_cannot_be_read_finds_nothing_marked() = runTest {
        val gallery = object : GalleryReader by inMemoryGallery(MutableStateFlow(library)) {
            override suspend fun resources(ids: Set<PlatformAssetId>): GalleryRead<List<GalleryAssetResources>> =
                GalleryRead.NotReadable
        }
        assertEquals(
            emptyMap(),
            MarkedPhotoLookup(gallery) { SelectionScope.Unrestricted }.markedIn(start, end, known = emptySet()),
        )
    }

    @Test
    fun a_photo_with_no_primary_resource_is_unmarked() = runTest {
        val liveOnly = LibraryAssets.photo(
            "L-LIVE",
            creationDate = "2026-06-05T12:00:00Z",
            resources = listOf(
                LibraryAssets.Resource(
                    ResourceKind.PAIRED_VIDEO,
                    "video/quicktime",
                    ReceivedPhotoName.mark("IMG_2.MOV", "k-live.mov", refA),
                ),
            ),
        )
        val gallery = inMemoryGallery(MutableStateFlow(listOf(liveOnly)))
        assertEquals(
            emptyMap(),
            MarkedPhotoLookup(gallery) { SelectionScope.Unrestricted }.markedIn(start, end, known = emptySet()),
        )
    }
}
