package app.snapsync.data.sharing

import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.mock.LibraryAssets
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The album denylist's ids: the photos captured since the cutoff in an album a messaging or social app made — one
 * listing, then one member read per denied album (O(albums), never O(assets)); what cannot be read denies nothing.
 */
class DenylistedAlbumsTest {

    private val cutoff = CaptureCutoff(at("2026-07-14T18:00:00Z"))

    private fun library(grant: LibraryGrant = LibraryGrant.GRANTED) = Library(
        LibraryAssets.photo("W1", DURING),
        LibraryAssets.photo("W2", BEFORE),
        LibraryAssets.photo("T1", DURING),
        LibraryAssets.photo("H1", DURING),
        LibraryAssets.photo("K1", DURING),
        grant = grant,
        albums = mapOf(
            "  whatsapp " to setOf("W1", "W2"),
            "TELEGRAM" to setOf("T1"),
            "Signal Hill Hike" to setOf("H1"),
            "WhatsApp Backup" to setOf("K1"),
        ),
    )

    @Test
    fun `only the denied albums are read, matched trimmed and ignoring case, members since the cutoff`() = runTest {
        val library = library()

        assertEquals(ids("W1", "T1").toSet(), DenylistedAlbums(library.reader).members(cutoff))
        assertEquals(
            listOf(
                "albums",
                "albumMembers(  whatsapp  from 2026-07-14T18:00:00Z)",
                "albumMembers(TELEGRAM from 2026-07-14T18:00:00Z)",
            ),
            library.calls,
            "W2 was captured before the cutoff; a user's own album is never read",
        )
    }

    @Test
    fun `an unreadable album listing denies nothing`() = runTest {
        assertEquals(emptySet(), DenylistedAlbums(library(LibraryGrant.DENIED).reader).members(cutoff))
    }

    @Test
    fun `an album whose members cannot be read denies nothing of its own`() = runTest {
        val library = library()
        library.unreadableMembers = setOf("TELEGRAM")
        assertEquals(ids("W1").toSet(), DenylistedAlbums(library.reader).members(cutoff))
    }
}
