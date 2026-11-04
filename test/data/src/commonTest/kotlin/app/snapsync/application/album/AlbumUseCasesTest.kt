package app.snapsync.application.album

import app.snapsync.application.EntryPointCall
import app.snapsync.data.album.AlbumCreation
import app.snapsync.data.album.AlbumFiling
import app.snapsync.data.album.AlbumLibrary
import app.snapsync.data.album.AlbumMap
import app.snapsync.data.membership.LEDGER
import app.snapsync.data.membership.active
import app.snapsync.data.membership.sharedOver
import app.snapsync.data.receiving.ANNA
import app.snapsync.data.receiving.Store
import app.snapsync.domain.album.AlbumAsk
import app.snapsync.domain.album.AlbumChoice
import app.snapsync.domain.album.AlbumEntry
import app.snapsync.domain.album.AlbumFill
import app.snapsync.domain.album.EventAlbumId
import app.snapsync.domain.album.ImportAlbumVerdict
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.levers.WriteKind
import app.snapsync.mock.DatabasesMock
import app.snapsync.ports.LibraryAlbumKind
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.WriteOutcome
import app.snapsync.testids.testAssetId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import app.snapsync.data.receiving.EVENT as RECEIVED_EVENT

/** The event album's use cases over the real album map and a scripted library. */
@OptIn(EntryPointCall::class, AppProcessOnly::class)
class AlbumUseCasesTest {

    private val device = DatabasesMock()
    private val map = AlbumMap(sharedOver(device))
    private val album = EventAlbumId("album-1")
    private val assets = listOf(AssetId.parse("A")!!, AssetId.parse("B")!!)

    private val libraryOnly = active()
    private val saving = libraryOnly.copy(share = libraryOnly.share.copy(album = AlbumChoice.SaveToAlbum))
    private val event = saving.event.id

    private fun ensure(library: AlbumLibrary) = EnsureEventAlbum(map, AlbumFiling(library), AlbumCreation(library))

    private suspend fun ensured(
        library: AlbumLibrary,
        ask: AlbumAsk = AlbumAsk.OptIn,
        membership: Membership.Active = saving,
        access: PhotoAccess = PhotoAccess.FULL,
    ) = ensure(library)(membership, access, ask)

    // ---- ensuring the album ----------------------------------------------------------------------------------------

    @Test
    fun `no album is made when the member chose none, or without usable access`() = runTest {
        val library = AlbumLibrary()
        assertEquals(EnsureAlbumOutcome.NotWanted, ensured(library, membership = libraryOnly))
        assertEquals(EnsureAlbumOutcome.NoAccess, ensured(library, access = PhotoAccess.NONE))
        assertEquals(emptyList(), library.titles)
    }

    @Test
    fun `the first ensure makes the album, titled with the event's name, and remembers it`() = runTest {
        val library = AlbumLibrary(created = "album-new")
        assertEquals(EnsureAlbumOutcome.Created(EventAlbumId("album-new")), ensured(library, AlbumAsk.Replay))
        assertEquals(listOf(saving.event.name.text), library.titles)
        assertEquals(AlbumEntry.Mapped(EventAlbumId("album-new"), AlbumFill.NotYetFilled), map.entry(event))
    }

    @Test
    fun `an album the library does not make is not remembered`() = runTest {
        assertEquals(EnsureAlbumOutcome.NotCreated, ensured(AlbumLibrary(created = null)))
        assertEquals(AlbumEntry.None, map.entry(event))
    }

    @Test
    fun `an album that is still there is reused, never duplicated`() = runTest {
        map.remember(event, album)
        val library = AlbumLibrary(existing = setOf("album-1"))
        assertEquals(EnsureAlbumOutcome.Reused(album), ensured(library))
        assertEquals(emptyList(), library.titles)
    }

    @Test
    fun `a deleted album is recreated on an opt-in and replaces the old one`() = runTest {
        map.remember(event, album)
        assertEquals(
            EnsureAlbumOutcome.Created(EventAlbumId("album-new")),
            ensured(AlbumLibrary(created = "album-new")),
        )
        assertEquals(AlbumEntry.Mapped(EventAlbumId("album-new"), AlbumFill.NotYetFilled), map.entry(event))
    }

    @Test
    fun `a deleted album stays deleted without an opt-in`() = runTest {
        map.remember(event, album)
        val library = AlbumLibrary()
        assertEquals(EnsureAlbumOutcome.KeptDeleted, ensured(library, AlbumAsk.Replay))
        assertEquals(emptyList(), library.titles)
        assertEquals(AlbumEntry.Mapped(album, AlbumFill.NotYetFilled), map.entry(event))
    }

    @Test
    fun `a folder that never held a photo is reused although it does not resolve`() = runTest {
        map.remember(event, album)
        assertEquals(EnsureAlbumOutcome.Reused(album), ensured(AlbumLibrary(LibraryAlbumKind.FOLDER), AlbumAsk.Replay))
    }

    @Test
    fun `a map that cannot be read makes no album`() = runTest {
        map.remember(event, album)
        device.lock()
        val library = AlbumLibrary()
        assertEquals(EnsureAlbumOutcome.Unreadable, ensured(library))
        assertEquals(emptyList(), library.titles)
    }

    @Test
    fun `an album made but not remembered is still the one this ensure answers`() = runTest {
        map.entry(event) // the store opens before a fault can be armed on it
        device.fail(LEDGER, "eventAlbum", WriteKind.INSERT)
        assertEquals(
            EnsureAlbumOutcome.Created(EventAlbumId("album-new")),
            ensured(AlbumLibrary(created = "album-new")),
        )
    }

    // ---- placing own photos ----------------------------------------------------------------------------------------

    @Test
    fun `own photos are added to the album the app made`() = runTest {
        map.remember(event, album)
        val library = AlbumLibrary()
        assertEquals(PlaceOutcome.Placed, PlaceOwnPhotos(map, AlbumFiling(library))(saving, assets))
        assertEquals(listOf("album-1" to setOf(PlatformAssetId("A"), PlatformAssetId("B"))), library.added)
    }

    @Test
    fun `an add the library refuses is not placed, and nothing is placed when not chosen`() = runTest {
        map.remember(event, album)
        val refusing = AlbumLibrary(adds = WriteOutcome.Failed("x"))
        assertEquals(PlaceOutcome.NotPlaced, PlaceOwnPhotos(map, AlbumFiling(refusing))(saving, assets))

        val library = AlbumLibrary()
        assertEquals(PlaceOutcome.Skipped, PlaceOwnPhotos(map, AlbumFiling(library))(libraryOnly, assets))
        assertEquals(emptyList(), library.added)
    }

    // ---- placing received photos -----------------------------------------------------------------------------------

    @Test
    fun `received photos moved into a folder album mark it filled`() = runTest {
        map.remember(event, album)
        val folder = AlbumLibrary(LibraryAlbumKind.FOLDER)
        assertEquals(PlaceOutcome.Placed, PlaceReceivedPhotos(map, AlbumFiling(folder))(event, assets))
        assertEquals(AlbumEntry.Mapped(album, AlbumFill.Filled), map.entry(event))
    }

    @Test
    fun `received photos added to a collection mark nothing, and a refused move marks nothing`() = runTest {
        map.remember(event, album)
        assertEquals(PlaceOutcome.Placed, PlaceReceivedPhotos(map, AlbumFiling(AlbumLibrary()))(event, assets))
        val refused = AlbumLibrary(LibraryAlbumKind.FOLDER, adds = WriteOutcome.Failed("x"))
        assertEquals(PlaceOutcome.NotPlaced, PlaceReceivedPhotos(map, AlbumFiling(refused))(event, assets))
        assertEquals(AlbumEntry.Mapped(album, AlbumFill.NotYetFilled), map.entry(event))
    }

    @Test
    fun `received photos go nowhere without an album, and a mark that cannot be written still placed them`() =
        runTest {
            assertEquals(PlaceOutcome.Skipped, PlaceReceivedPhotos(map, AlbumFiling(AlbumLibrary()))(event, assets))

            map.remember(event, album)
            device.fail(LEDGER, "eventAlbum", WriteKind.UPDATE)
            val folder = AlbumLibrary(LibraryAlbumKind.FOLDER)
            assertEquals(PlaceOutcome.Placed, PlaceReceivedPhotos(map, AlbumFiling(folder))(event, assets))
        }

    // ---- the import's album ----------------------------------------------------------------------------------------

    @Test
    fun `an import files into the chosen album while it is there`() = runTest {
        map.remember(event, album)
        assertEquals(
            ImportAlbumVerdict.Into(album),
            AlbumForImport(map, AlbumFiling(AlbumLibrary(existing = setOf("album-1"))))(saving),
        )
    }

    @Test
    fun `an import files into no album when none is chosen, made, readable or left`() = runTest {
        val camera = ImportAlbumVerdict.CameraFolder
        val library = AlbumFiling(AlbumLibrary(LibraryAlbumKind.FOLDER))
        assertEquals(camera, AlbumForImport(map, library)(saving))
        assertEquals(camera, AlbumForImport(map, library)(libraryOnly))

        map.remember(event, album)
        map.markFilled(event)
        assertEquals(camera, AlbumForImport(map, library)(saving), "a filled folder that is gone")

        device.lock()
        assertEquals(camera, AlbumForImport(AlbumMap(sharedOver(device)), library)(saving))
    }

    @Test
    fun `an import into a folder album marks it filled, into a collection it does not`() = runTest {
        map.remember(event, album)
        MarkAlbumFilled(map, AlbumFiling(AlbumLibrary()))(event, album)
        assertEquals(AlbumEntry.Mapped(album, AlbumFill.NotYetFilled), map.entry(event))

        MarkAlbumFilled(map, AlbumFiling(AlbumLibrary(LibraryAlbumKind.FOLDER)))(event, album)
        assertEquals(AlbumEntry.Mapped(album, AlbumFill.Filled), map.entry(event))
    }

    @Test
    fun `a fill mark that cannot be written is logged, never thrown`() = runTest {
        map.remember(event, album)
        device.fail(LEDGER, "eventAlbum", WriteKind.UPDATE)
        MarkAlbumFilled(map, AlbumFiling(AlbumLibrary(LibraryAlbumKind.FOLDER)))(event, album)
        assertEquals(AlbumEntry.Mapped(album, AlbumFill.NotYetFilled), map.entry(event))
    }

    // ---- what was received -----------------------------------------------------------------------------------------

    @Test
    fun `what was received for the event is read in order, or that it cannot be`() = runTest {
        val store = Store()
        store.sql(
            "INSERT INTO downloadAsset VALUES ('${ANNA.value}', 'Y', 'IMPORTED', '2026-07-15T10:00:00Z', 'LOCAL-B')",
            "INSERT INTO downloadAsset VALUES ('${ANNA.value}', 'X', 'IMPORTED', '2026-07-15T10:00:00Z', 'LOCAL-A')",
            "INSERT INTO eventRef VALUES ('${RECEIVED_EVENT.value}', '${ANNA.value}', 'X')",
            "INSERT INTO eventRef VALUES ('${RECEIVED_EVENT.value}', '${ANNA.value}', 'Y')",
        )
        assertEquals(
            ReceivedAlbumRead.Read(listOf(testAssetId("LOCAL-A"), testAssetId("LOCAL-B"))),
            ReceivedAlbumPhotos(store.counts)(RECEIVED_EVENT),
        )

        store.databases.lock()
        assertIs<ReceivedAlbumRead.Unavailable>(ReceivedAlbumPhotos(store.counts)(RECEIVED_EVENT))
    }
}
