package app.snapsync.data.store

import app.snapsync.domain.album.AlbumChoice
import app.snapsync.domain.album.AlbumFill
import app.snapsync.domain.album.EventAlbumId
import kotlin.test.Test
import kotlin.test.assertEquals

/** The event album's columns keep the bytes every build wrote: a 1/0 flag for the choice and the mark, the id's text. */
class AlbumColumnAdaptersTest {

    @Test
    fun `the album choice is the flag every build wrote`() {
        assertEquals(1L, AlbumChoiceColumnAdapter.encode(AlbumChoice.SaveToAlbum))
        assertEquals(0L, AlbumChoiceColumnAdapter.encode(AlbumChoice.LibraryOnly))
        assertEquals(AlbumChoice.SaveToAlbum, AlbumChoiceColumnAdapter.decode(1L))
        assertEquals(AlbumChoice.LibraryOnly, AlbumChoiceColumnAdapter.decode(0L))
    }

    @Test
    fun `the fill mark is the flag every build wrote`() {
        assertEquals(1L, AlbumFillColumnAdapter.encode(AlbumFill.Filled))
        assertEquals(0L, AlbumFillColumnAdapter.encode(AlbumFill.NotYetFilled))
        assertEquals(AlbumFill.Filled, AlbumFillColumnAdapter.decode(1L))
        assertEquals(AlbumFill.NotYetFilled, AlbumFillColumnAdapter.decode(0L))
    }

    @Test
    fun `an album's id is the text the platform wrote`() {
        assertEquals("DCIM/SnapSync/Party", EventAlbumIdColumnAdapter.encode(EventAlbumId("DCIM/SnapSync/Party")))
        assertEquals(EventAlbumId("ABC/L0/001"), EventAlbumIdColumnAdapter.decode("ABC/L0/001"))
    }
}
