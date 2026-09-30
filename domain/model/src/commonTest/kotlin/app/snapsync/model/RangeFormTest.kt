package app.snapsync.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The member's uncommitted choices (capability `event-album`): whether the album is offered at all is part of them. */
class RangeFormTest {

    @Test
    fun `an untouched form offers the album and has it on`() {
        val form = RangeForm()
        assertTrue(form.albumOffered)
        assertTrue(form.saveToAlbum)
    }

    @Test
    fun `a form on a phone without albums is a different form`() {
        assertNotEquals(RangeForm(), RangeForm(albumOffered = false, saveToAlbum = false))
        assertEquals(RangeForm(albumOffered = false), RangeForm().copy(albumOffered = false))
    }

    @Test
    fun `the offered-album flag survives the wire`() {
        // The form rides the control channel's UiState to a mirrored screen: a phone without albums must stay one.
        for (form in listOf(RangeForm(), RangeForm(albumOffered = false, saveToAlbum = false))) {
            assertEquals(form, Json.decodeFromString<RangeForm>(Json.encodeToString(form)))
        }
        assertEquals(RangeForm(), Json.decodeFromString<RangeForm>("{}"), "an absent flag reads as the iPhone's answer")
    }
}
