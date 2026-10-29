package app.snapsync.gallery

import app.snapsync.data.sharing.SelectionSnapshots
import app.snapsync.data.sharing.SelectionState
import app.snapsync.format.TimestampText
import app.snapsync.mock.LibraryAssets
import app.snapsync.mock.id
import app.snapsync.mock.inMemoryGallery
import app.snapsync.model.AssetRef
import app.snapsync.model.ReceivedPhotoName
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ResourceKind
import app.snapsync.ports.SelectionSnapshot
import app.snapsync.services.gallery.MarkedPhotoLookup
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The join's read of the library's SnapSync-marked photos (capability `receiving-photos`), over the in-memory gallery. */
class MarkedPhotoLookupTest {

    private val start = TimestampText.parse("2026-06-01T00:00:00Z")
    private val end = TimestampText.parse("2026-06-10T00:00:00Z")
    private val refA = AssetRef(testDeviceId("dev-a"), testAssetId("SRC-A"))
    private val refB = AssetRef(testDeviceId("dev-b"), testAssetId("SRC-B"))

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
        received(
            "L-EARLY",
            AssetRef(testDeviceId("dev-c"), testAssetId("SRC-C")),
            date = "2026-05-20T12:00:00Z",
        ), // outside the event
    )

    /** No selection read yet — the state under a full grant, and the gap before a partial grant's first read. */
    private fun unread() = SelectionSnapshots()

    /** A selection read of [assets], as the gallery's selection observer publishes it. */
    private suspend fun selected(assets: List<GalleryAssetResources>): SelectionSnapshots =
        SelectionSnapshots().also { it.published(flowOf(SelectionSnapshot(assets))) {}.collect() }

    /** The gallery's reads, recording every id a resource read asked for. */
    private class Recording(private val reader: GalleryReader) : GalleryReader by reader {
        val resourceReads = mutableListOf<Set<PlatformAssetId>>()
        override suspend fun resources(ids: Set<PlatformAssetId>): GalleryRead<List<GalleryAssetResources>> =
            reader.resources(ids).also { resourceReads += ids }
    }

    @Test
    fun a_full_grant_finds_the_marked_photos_in_the_window() = runTest {
        val gallery = Recording(inMemoryGallery(MutableStateFlow(library)))
        val found = MarkedPhotoLookup(gallery, unread()).markedIn(start, end, known = emptySet())
        assertEquals(
            mapOf(
                ReceivedPhotoName.token(refA) to testAssetId("L-A"),
                ReceivedPhotoName.token(refB) to testAssetId("L-B"),
            ),
            found,
        )
        assertTrue(
            PlatformAssetId("L-EARLY") !in gallery.resourceReads.flatten(),
            "a photo outside the window is never read",
        )
    }

    @Test
    fun a_window_reaching_before_the_event_finds_a_photo_received_from_before_it() = runTest {
        // Capability `receiving-photos`: another member's range may start before the event, so the photo they took
        // the week before is a received photo too — found when the caller's window reaches it.
        val found = MarkedPhotoLookup(inMemoryGallery(MutableStateFlow(library)), unread())
            .markedIn(TimestampText.parse("2026-05-15T00:00:00Z"), end, known = emptySet())
        assertEquals(
            testAssetId("L-EARLY"),
            found?.get(ReceivedPhotoName.token(AssetRef(testDeviceId("dev-c"), testAssetId("SRC-C")))),
        )
    }

    @Test
    fun a_selection_snapshot_is_searched_for_marked_photos_whatever_their_date() {
        // The pre-check a selection change makes: no date narrows it, so a received photo from before the event pauses
        // sharing too. The member's own unmarked photo is never a candidate.
        val found = MarkedPhotoLookup(inMemoryGallery(MutableStateFlow(library)), unread())
            .markedAmong(SelectionState.Read(library), known = setOf(testAssetId("L-A")))
        assertEquals(setOf(testAssetId("L-B"), testAssetId("L-EARLY")), found.values.toSet())
    }

    @Test
    fun a_known_asset_is_never_read() = runTest {
        val gallery = Recording(inMemoryGallery(MutableStateFlow(library)))
        val found = MarkedPhotoLookup(gallery, unread()).markedIn(start, end, known = setOf(testAssetId("L-A")))
        assertEquals(mapOf(ReceivedPhotoName.token(refB) to testAssetId("L-B")), found)
        assertTrue(PlatformAssetId("L-A") !in gallery.resourceReads.flatten())
    }

    @Test
    fun a_partial_grant_reads_the_snapshot_and_not_the_library() = runTest {
        val gallery = Recording(inMemoryGallery(MutableStateFlow(library), MutableStateFlow(LibraryGrant.LIMITED)))
        val selection = selected(library.filter { it.id.value != "L-B" }) // L-B was not selected
        val found = MarkedPhotoLookup(gallery, selection).markedIn(start, end, known = emptySet())
        assertEquals(mapOf(ReceivedPhotoName.token(refA) to testAssetId("L-A")), found)
        assertTrue(gallery.resourceReads.isEmpty(), "the selection already carries every name")
    }

    @Test
    fun an_unread_selection_finds_nothing_and_no_grant_reads_nothing() = runTest {
        val limited = inMemoryGallery(MutableStateFlow(library), MutableStateFlow(LibraryGrant.LIMITED))
        assertEquals(emptyMap(), MarkedPhotoLookup(limited, unread()).markedIn(start, end, emptySet()))
        val denied = inMemoryGallery(MutableStateFlow(library), MutableStateFlow(LibraryGrant.DENIED))
        assertNull(MarkedPhotoLookup(denied, unread()).markedIn(start, end, emptySet()), "unreadable")
        assertNull(
            MarkedPhotoLookup(denied, selected(library)).markedIn(start, end, emptySet()),
            "a selection held from an earlier grant is not readable under none",
        )
    }

    @Test
    fun a_known_asset_in_the_selection_is_passed_over_and_a_primary_with_no_name_is_unmarked() = runTest {
        val gallery = inMemoryGallery(MutableStateFlow(library), MutableStateFlow(LibraryGrant.LIMITED))
        val nameless = LibraryAssets.photo(
            "L-X",
            creationDate = "2026-06-05T12:00:00Z",
            resources = listOf(LibraryAssets.primaryResource("", "image/heic")),
        )
        val found = MarkedPhotoLookup(gallery, selected(library + nameless))
            .markedIn(start, end, known = setOf(testAssetId("L-A")))
        assertEquals(mapOf(ReceivedPhotoName.token(refB) to testAssetId("L-B")), found)
    }

    @Test
    fun a_library_whose_resources_cannot_be_read_finds_nothing_marked() = runTest {
        val gallery = object : GalleryReader by inMemoryGallery(MutableStateFlow(library)) {
            override suspend fun resources(ids: Set<PlatformAssetId>): GalleryRead<List<GalleryAssetResources>> =
                GalleryRead.NotReadable
        }
        assertEquals(emptyMap(), MarkedPhotoLookup(gallery, unread()).markedIn(start, end, known = emptySet()))
    }

    @Test
    fun a_library_that_cannot_be_walked_under_a_full_grant_is_unreadable() = runTest {
        // Not "nothing marked": the caller asks again once the library can be read.
        val gallery = object : GalleryReader by inMemoryGallery(MutableStateFlow(library)) {
            override suspend fun assets(filter: AssetFilter): GalleryRead<List<GalleryAsset>> = GalleryRead.NotReadable
        }
        assertNull(MarkedPhotoLookup(gallery, unread()).markedIn(start, end, known = emptySet()))
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
        assertEquals(emptyMap(), MarkedPhotoLookup(gallery, unread()).markedIn(start, end, known = emptySet()))
    }
}
