package app.snapsync.presentation

import app.snapsync.model.AlbumKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The member's uncommitted choices (capability `event-album`): how the phone holds an album rides with them. */
class RangeFormTest {

    @Test
    fun `an untouched form has the album on — as a collection`() {
        val form = RangeForm()
        assertEquals(AlbumKind.COLLECTION, form.albumKind)
        assertTrue(form.saveToAlbum)
    }

    @Test
    fun `a form on a phone with folder albums is a different form`() {
        assertNotEquals(RangeForm(), RangeForm(albumKind = AlbumKind.FOLDER))
        assertEquals(RangeForm(albumKind = AlbumKind.FOLDER), RangeForm().copy(albumKind = AlbumKind.FOLDER))
    }
}
