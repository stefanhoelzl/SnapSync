package app.snapsync.data.album

import app.snapsync.data.membership.name
import app.snapsync.data.membership.sharedOver
import app.snapsync.data.store.StoreWrite
import app.snapsync.domain.album.AlbumAdd
import app.snapsync.domain.album.AlbumContents
import app.snapsync.domain.album.AlbumEntry
import app.snapsync.domain.album.AlbumFill
import app.snapsync.domain.album.AlbumPresence
import app.snapsync.domain.album.EventAlbumId
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.inMemoryGallery
import app.snapsync.ports.GalleryAlbum
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.LibraryAlbumKind
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.WriteOutcome
import app.snapsync.testids.testEventId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The photo library's album calls, scripted and recorded; every other read is the in-memory library's. */
internal class AlbumLibrary(
    override val albumKind: LibraryAlbumKind = LibraryAlbumKind.COLLECTION,
    private val created: String? = "album-new",
    private val existing: Set<String> = emptySet(),
    private val readable: Boolean = true,
    private val adds: WriteOutcome = WriteOutcome.Ok,
) : GalleryReader by inMemoryGallery(MutableStateFlow(emptyList())) {
    val titles = mutableListOf<String>()
    val added = mutableListOf<Pair<String, Set<PlatformAssetId>>>()

    override suspend fun createAlbum(title: String): PlatformAlbumId? = created?.let(::PlatformAlbumId).also {
        titles += title
    }

    override suspend fun albumsById(ids: Set<PlatformAlbumId>): GalleryRead<List<GalleryAlbum>> = when (readable) {
        true -> GalleryRead.Read(ids.filter { it.value in existing }.map { GalleryAlbum(it, "Party") })
        false -> GalleryRead.NotReadable
    }

    override suspend fun addToAlbum(album: PlatformAlbumId, assets: Set<PlatformAssetId>): WriteOutcome =
        adds.also { added += album.value to assets }
}

class AlbumDataTest {

    private val device = DatabasesMock()
    private val event = testEventId("E1")
    private val album = EventAlbumId("album-1")

    // ---- the album map -------------------------------------------------------------------------------------------

    @Test
    fun `an event with no album reads as none`() {
        assertEquals(AlbumEntry.None, AlbumMap(sharedOver(device)).entry(event))
    }

    @Test
    fun `a remembered album is unfilled until marked, and a new one for the event starts unfilled again`() {
        val map = AlbumMap(sharedOver(device))
        assertEquals(StoreWrite.Written, map.remember(event, album))
        assertEquals(AlbumEntry.Mapped(album, AlbumFill.NotYetFilled), map.entry(event))

        assertEquals(StoreWrite.Written, map.markFilled(event))
        assertEquals(AlbumEntry.Mapped(album, AlbumFill.Filled), AlbumMap(sharedOver(device)).entry(event))

        map.remember(event, EventAlbumId("album-2"))
        assertEquals(AlbumEntry.Mapped(EventAlbumId("album-2"), AlbumFill.NotYetFilled), map.entry(event))
    }

    @Test
    fun `each event keeps its own album`() {
        val map = AlbumMap(sharedOver(device))
        map.remember(event, album)
        map.remember(testEventId("E2"), EventAlbumId("album-2"))
        assertEquals(AlbumEntry.Mapped(album, AlbumFill.NotYetFilled), AlbumMap(sharedOver(device)).entry(event))
    }

    @Test
    fun `a map that cannot be read is unreadable, never none, and its writes fail`() {
        AlbumMap(sharedOver(device)).remember(event, album)
        device.lock()
        val map = AlbumMap(sharedOver(device))

        assertEquals(AlbumEntry.Unreadable, map.entry(event))
        assertIs<StoreWrite.Failed>(map.remember(event, EventAlbumId("album-2")))
        assertIs<StoreWrite.Failed>(map.markFilled(event))
    }

    // ---- the library's albums --------------------------------------------------------------------------------------

    @Test
    fun `a collection library holds shared and received photos, a folder library received ones`() {
        assertEquals(AlbumContents.SharedAndReceived, AlbumFiling(AlbumLibrary(LibraryAlbumKind.COLLECTION)).contents)
        assertEquals(AlbumContents.Received, AlbumFiling(AlbumLibrary(LibraryAlbumKind.FOLDER)).contents)
    }

    @Test
    fun `an album resolves, is missing, or the library cannot say`() = runTest {
        assertEquals(AlbumPresence.Resolves, AlbumFiling(AlbumLibrary(existing = setOf("album-1"))).presence(album))
        assertEquals(AlbumPresence.Missing, AlbumFiling(AlbumLibrary()).presence(album))
        assertEquals(AlbumPresence.Unreadable, AlbumFiling(AlbumLibrary(readable = false)).presence(album))
    }

    @Test
    fun `an add hands the library the photos' own ids, and says whether it took them`() = runTest {
        val library = AlbumLibrary()
        val assets = listOf(AssetId.parse("A")!!, AssetId.parse("B")!!)
        assertEquals(AlbumAdd.Added, AlbumFiling(library).add(album, assets))
        assertEquals(listOf("album-1" to setOf(PlatformAssetId("A"), PlatformAssetId("B"))), library.added)

        assertEquals(AlbumAdd.NotAdded, AlbumFiling(AlbumLibrary(adds = WriteOutcome.Failed("x"))).add(album, assets))
    }

    @Test
    @OptIn(AppProcessOnly::class)
    fun `an album is made titled with the event's name, or not made`() = runTest {
        val library = AlbumLibrary(created = "album-9")
        assertEquals(NewAlbum.Created(EventAlbumId("album-9")), AlbumCreation(library).create(name("Party")))
        assertEquals(listOf("Party"), library.titles)

        assertEquals(NewAlbum.NotCreated, AlbumCreation(AlbumLibrary(created = null)).create(name("Party")))
    }

    // ---- the grant's changes ---------------------------------------------------------------------------------------

    @Test
    fun `each grant arrives with the one before it, the first with none`() = runTest {
        val grant = MutableStateFlow(LibraryGrant.GRANTED)
        val seen = mutableListOf<Pair<PhotoAccess?, PhotoAccess>>()
        val collecting = launch {
            PhotoAccessChanges(grant).changes.take(3).toList().forEach { seen += it.before to it.now }
        }
        yield()
        grant.value = LibraryGrant.DENIED
        yield()
        grant.value = LibraryGrant.LIMITED
        collecting.join()

        assertEquals(
            listOf(
                null to PhotoAccess.FULL,
                PhotoAccess.FULL to PhotoAccess.NONE,
                PhotoAccess.NONE to PhotoAccess.LIMITED,
            ),
            seen,
        )
    }
}
