package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The album denylist's matching rule (capability `photo-sharing`). The match is deliberately
 * **exact** (after trim, case-insensitive): a prefix or substring match would silently swallow a user's own
 * album that merely happens to start with a denied word, which is the false-drop this policy forbids.
 */
class SelectionCalibrationTest {

    @Test
    fun a_denylisted_title_matches() {
        assertTrue(SELECTION_CALIBRATION.isDenylistedAlbum("WhatsApp"))
        assertTrue(SELECTION_CALIBRATION.isDenylistedAlbum("Telegram"))
        assertTrue(SELECTION_CALIBRATION.isDenylistedAlbum("Instagram"))
    }

    @Test
    fun matching_is_case_insensitive() {
        assertTrue(SELECTION_CALIBRATION.isDenylistedAlbum("whatsapp"))
        assertTrue(SELECTION_CALIBRATION.isDenylistedAlbum("WHATSAPP"))
        assertTrue(SELECTION_CALIBRATION.isDenylistedAlbum("wHaTsApP"))
    }

    @Test
    fun surrounding_whitespace_does_not_defeat_the_match() {
        assertTrue(SELECTION_CALIBRATION.isDenylistedAlbum("  WhatsApp "))
    }

    @Test
    fun matching_is_exact_not_a_prefix_or_substring() {
        // The user's own albums. Each contains a denied word; none may be swallowed.
        assertFalse(SELECTION_CALIBRATION.isDenylistedAlbum("WhatsApp Backup"))
        assertFalse(SELECTION_CALIBRATION.isDenylistedAlbum("Signal Hill Hike"))
        assertFalse(SELECTION_CALIBRATION.isDenylistedAlbum("My Telegram Screenshots"))
        assertFalse(SELECTION_CALIBRATION.isDenylistedAlbum("Instagram-worthy"))
    }

    @Test
    fun an_ordinary_user_album_is_not_denylisted() {
        assertFalse(SELECTION_CALIBRATION.isDenylistedAlbum("Holiday 2026"))
        assertFalse(SELECTION_CALIBRATION.isDenylistedAlbum("Favourites"))
        assertFalse(SELECTION_CALIBRATION.isDenylistedAlbum(""))
    }
}
