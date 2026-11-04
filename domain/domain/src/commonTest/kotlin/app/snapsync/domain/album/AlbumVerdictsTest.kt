package app.snapsync.domain.album

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.sharing.PhotoAccess
import kotlin.test.Test
import kotlin.test.assertEquals

/** The event album's rules, one verdict each. */
class AlbumVerdictsTest {

    private val album = EventAlbumId("album-1")
    private val other = EventAlbumId("album-2")
    private val assets = listOf(AssetId.parse("A")!!, AssetId.parse("B")!!)
    private val mapped = AlbumEntry.Mapped(album, AlbumFill.Filled)

    // ---- what the phone's album holds ----------------------------------------------------------------------------

    @Test
    fun `a collection takes the member's own photos and a folder leaves them in the camera folder`() {
        assertEquals(OwnPhotoHome.IntoAlbum, AlbumContents.SharedAndReceived.ownPhotos)
        assertEquals(OwnPhotoHome.StayInCameraFolder, AlbumContents.Received.ownPhotos)
    }

    @Test
    fun `a collection that does not resolve is deleted, filled or not`() {
        AlbumFill.entries.forEach { fill ->
            assertEquals(AlbumStanding.Present, AlbumContents.SharedAndReceived.standing(fill, AlbumPresence.Resolves))
            assertEquals(AlbumStanding.Deleted, AlbumContents.SharedAndReceived.standing(fill, AlbumPresence.Missing))
            assertEquals(
                AlbumStanding.Deleted,
                AlbumContents.SharedAndReceived.standing(fill, AlbumPresence.Unreadable),
            )
        }
    }

    @Test
    fun `a folder that never held a photo is no album yet, never a deleted one`() {
        AlbumPresence.entries.forEach {
            assertEquals(AlbumStanding.Present, AlbumContents.Received.standing(AlbumFill.NotYetFilled, it))
        }
    }

    @Test
    fun `a folder that held a photo and does not resolve was deleted, emptied or renamed`() {
        assertEquals(AlbumStanding.Present, AlbumContents.Received.standing(AlbumFill.Filled, AlbumPresence.Resolves))
        assertEquals(AlbumStanding.Deleted, AlbumContents.Received.standing(AlbumFill.Filled, AlbumPresence.Missing))
        assertEquals(AlbumStanding.Deleted, AlbumContents.Received.standing(AlbumFill.Filled, AlbumPresence.Unreadable))
    }

    // ---- making the album ------------------------------------------------------------------------------------------

    @Test
    fun `an album is wanted only when chosen, and made only under full or limited access`() {
        assertEquals(AlbumWishVerdict.NotWanted, AlbumWishVerdict.of(AlbumChoice.LibraryOnly, PhotoAccess.FULL))
        assertEquals(AlbumWishVerdict.Wanted, AlbumWishVerdict.of(AlbumChoice.SaveToAlbum, PhotoAccess.FULL))
        assertEquals(AlbumWishVerdict.Wanted, AlbumWishVerdict.of(AlbumChoice.SaveToAlbum, PhotoAccess.LIMITED))
        assertEquals(AlbumWishVerdict.NoAccess, AlbumWishVerdict.of(AlbumChoice.SaveToAlbum, PhotoAccess.NONE))
    }

    @Test
    fun `a present album is reused whoever asks`() {
        AlbumAsk.entries.forEach {
            assertEquals(StoredAlbumVerdict.Reuse(album), StoredAlbumVerdict.of(album, AlbumStanding.Present, it))
        }
    }

    @Test
    fun `a deleted album comes back only on the member's opt-in`() {
        assertEquals(StoredAlbumVerdict.Recreate, StoredAlbumVerdict.of(album, AlbumStanding.Deleted, AlbumAsk.OptIn))
        assertEquals(
            StoredAlbumVerdict.KeptDeleted,
            StoredAlbumVerdict.of(album, AlbumStanding.Deleted, AlbumAsk.Replay),
        )
    }

    // ---- importing into it -----------------------------------------------------------------------------------------

    @Test
    fun `an import files into the album only when it is chosen and there`() {
        assertEquals(
            ImportAlbumVerdict.Into(album),
            ImportAlbumVerdict.of(AlbumChoice.SaveToAlbum, album, AlbumStanding.Present),
        )
        assertEquals(
            ImportAlbumVerdict.CameraFolder,
            ImportAlbumVerdict.of(AlbumChoice.SaveToAlbum, album, AlbumStanding.Deleted),
        )
        AlbumStanding.entries.forEach {
            assertEquals(ImportAlbumVerdict.CameraFolder, ImportAlbumVerdict.of(AlbumChoice.LibraryOnly, album, it))
        }
    }

    // ---- placing ---------------------------------------------------------------------------------------------------

    @Test
    fun `own photos are placed into the album that exists, on a phone whose albums take them`() {
        assertEquals(
            OwnPlacementVerdict.Place(album, assets),
            OwnPlacementVerdict.of(AlbumChoice.SaveToAlbum, AlbumContents.SharedAndReceived, mapped, assets),
        )
    }

    @Test
    fun `own photos are never placed when not chosen, on a folder phone, with no album, or with nothing to place`() {
        val skip = OwnPlacementVerdict.Skip
        val contents = AlbumContents.SharedAndReceived
        assertEquals(skip, OwnPlacementVerdict.of(AlbumChoice.LibraryOnly, contents, mapped, assets))
        assertEquals(skip, OwnPlacementVerdict.of(AlbumChoice.SaveToAlbum, AlbumContents.Received, mapped, assets))
        assertEquals(skip, OwnPlacementVerdict.of(AlbumChoice.SaveToAlbum, contents, AlbumEntry.None, assets))
        assertEquals(skip, OwnPlacementVerdict.of(AlbumChoice.SaveToAlbum, contents, AlbumEntry.Unreadable, assets))
        assertEquals(skip, OwnPlacementVerdict.of(AlbumChoice.SaveToAlbum, contents, mapped, emptyList()))
    }

    @Test
    fun `received photos are filed into the album that exists, never into one not made`() {
        assertEquals(ReceivedPlacementVerdict.Place(album, assets), ReceivedPlacementVerdict.of(mapped, assets))
        assertEquals(ReceivedPlacementVerdict.Skip, ReceivedPlacementVerdict.of(AlbumEntry.None, assets))
        assertEquals(ReceivedPlacementVerdict.Skip, ReceivedPlacementVerdict.of(AlbumEntry.Unreadable, assets))
        assertEquals(ReceivedPlacementVerdict.Skip, ReceivedPlacementVerdict.of(mapped, emptyList()))
    }

    // ---- the fill mark ---------------------------------------------------------------------------------------------

    @Test
    fun `an import marks a folder album the event still maps to, and nothing else`() {
        assertEquals(FillMarkVerdict.Mark, FillMarkVerdict.afterImport(AlbumContents.Received, mapped, album))
        assertEquals(FillMarkVerdict.Leave, FillMarkVerdict.afterImport(AlbumContents.Received, mapped, other))
        assertEquals(FillMarkVerdict.Leave, FillMarkVerdict.afterImport(AlbumContents.Received, AlbumEntry.None, album))
        assertEquals(
            FillMarkVerdict.Leave,
            FillMarkVerdict.afterImport(AlbumContents.Received, AlbumEntry.Unreadable, album),
        )
        assertEquals(FillMarkVerdict.Leave, FillMarkVerdict.afterImport(AlbumContents.SharedAndReceived, mapped, album))
    }

    @Test
    fun `a gather's move marks a folder album only once the library took it`() {
        assertEquals(FillMarkVerdict.Mark, FillMarkVerdict.afterMove(AlbumContents.Received, AlbumAdd.Added))
        assertEquals(FillMarkVerdict.Leave, FillMarkVerdict.afterMove(AlbumContents.Received, AlbumAdd.NotAdded))
        assertEquals(FillMarkVerdict.Leave, FillMarkVerdict.afterMove(AlbumContents.SharedAndReceived, AlbumAdd.Added))
    }

    // ---- gathering -------------------------------------------------------------------------------------------------

    @Test
    fun `a gather places received photos, and own ones where the album takes them`() {
        assertEquals(
            GatherScopeVerdict.Gather(OwnPhotoHome.IntoAlbum),
            GatherScopeVerdict.of(AlbumChoice.SaveToAlbum, PhotoAccess.LIMITED, AlbumContents.SharedAndReceived),
        )
        assertEquals(
            GatherScopeVerdict.Gather(OwnPhotoHome.StayInCameraFolder),
            GatherScopeVerdict.of(AlbumChoice.SaveToAlbum, PhotoAccess.FULL, AlbumContents.Received),
        )
    }

    @Test
    fun `a gather places nothing when not chosen or without usable access`() {
        val contents = AlbumContents.SharedAndReceived
        assertEquals(
            GatherScopeVerdict.NotWanted,
            GatherScopeVerdict.of(AlbumChoice.LibraryOnly, PhotoAccess.FULL, contents),
        )
        assertEquals(
            GatherScopeVerdict.NoAccess,
            GatherScopeVerdict.of(AlbumChoice.SaveToAlbum, PhotoAccess.NONE, contents),
        )
    }

    // ---- the photo grant -------------------------------------------------------------------------------------------

    @Test
    fun `no usable access does nothing`() {
        listOf(null, PhotoAccess.NONE, PhotoAccess.LIMITED, PhotoAccess.FULL).forEach {
            assertEquals(GrantVerdict.Nothing, GrantVerdict.of(it, PhotoAccess.NONE))
        }
    }

    @Test
    fun `the launch's first look and a usable-to-usable change only ensure the album`() {
        assertEquals(GrantVerdict.EnsureOnly, GrantVerdict.of(null, PhotoAccess.FULL))
        assertEquals(GrantVerdict.EnsureOnly, GrantVerdict.of(PhotoAccess.LIMITED, PhotoAccess.FULL))
        assertEquals(GrantVerdict.EnsureOnly, GrantVerdict.of(PhotoAccess.FULL, PhotoAccess.LIMITED))
    }

    @Test
    fun `access becoming usable while the app runs ensures, then gathers`() {
        assertEquals(GrantVerdict.EnsureAndGather, GrantVerdict.of(PhotoAccess.NONE, PhotoAccess.LIMITED))
        assertEquals(GrantVerdict.EnsureAndGather, GrantVerdict.of(PhotoAccess.NONE, PhotoAccess.FULL))
    }
}
