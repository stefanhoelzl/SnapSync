package app.snapsync.model

import kotlinx.serialization.json.Json
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

    @Test
    fun `the album kind survives the wire`() {
        // The form rides the control channel's UiState to a mirrored screen: an Android phone must stay one.
        for (form in listOf(RangeForm(), RangeForm(albumKind = AlbumKind.FOLDER, saveToAlbum = false))) {
            assertEquals(form, Json.decodeFromString<RangeForm>(Json.encodeToString(form)))
        }
        assertEquals(RangeForm(), Json.decodeFromString<RangeForm>("{}"), "an absent kind reads as the iPhone's answer")
    }
}
