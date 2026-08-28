package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The album denylist's matching rule (capability `photo-selection-policy`). The match is deliberately
 * **exact** (after trim, case-insensitive): a prefix or substring match would silently swallow a user's own
 * album that merely happens to start with a denied word, which is the false-drop this policy forbids.
 */
class AlbumDenylistTest {

    @Test
    fun a_denylisted_title_matches() {
        assertTrue(isDenylistedAlbum("WhatsApp"))
        assertTrue(isDenylistedAlbum("Telegram"))
        assertTrue(isDenylistedAlbum("Instagram"))
    }

    @Test
    fun matching_is_case_insensitive() {
        assertTrue(isDenylistedAlbum("whatsapp"))
        assertTrue(isDenylistedAlbum("WHATSAPP"))
        assertTrue(isDenylistedAlbum("wHaTsApP"))
    }

    @Test
    fun surrounding_whitespace_does_not_defeat_the_match() {
        assertTrue(isDenylistedAlbum("  WhatsApp "))
    }

    @Test
    fun matching_is_exact_not_a_prefix_or_substring() {
        // The user's own albums. Each contains a denied word; none may be swallowed.
        assertFalse(isDenylistedAlbum("WhatsApp Backup"))
        assertFalse(isDenylistedAlbum("Signal Hill Hike"))
        assertFalse(isDenylistedAlbum("My Telegram Screenshots"))
        assertFalse(isDenylistedAlbum("Instagram-worthy"))
    }

    @Test
    fun an_ordinary_user_album_is_not_denylisted() {
        assertFalse(isDenylistedAlbum("Holiday 2026"))
        assertFalse(isDenylistedAlbum("Favourites"))
        assertFalse(isDenylistedAlbum(""))
    }
}
