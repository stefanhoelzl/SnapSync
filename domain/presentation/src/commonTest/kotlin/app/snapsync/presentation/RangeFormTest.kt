package app.snapsync.presentation

import app.snapsync.domain.album.AlbumContents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The member's uncommitted choices (capability `event-album`): how the phone holds an album rides with them. */
class RangeFormTest {

    @Test
    fun `an untouched form has the album on — holding shared and received photos`() {
        val form = RangeForm()
        assertEquals(AlbumContents.SharedAndReceived, form.albumContents)
        assertTrue(form.saveToAlbum)
    }

    @Test
    fun `a form on a phone with folder albums is a different form`() {
        assertNotEquals(RangeForm(), RangeForm(albumContents = AlbumContents.Received))
        assertEquals(
            RangeForm(albumContents = AlbumContents.Received),
            RangeForm().copy(albumContents = AlbumContents.Received),
        )
    }
}
