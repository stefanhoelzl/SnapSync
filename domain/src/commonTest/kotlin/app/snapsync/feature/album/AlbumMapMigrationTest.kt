package app.snapsync.feature.album

import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.StoredProtection
import kotlin.test.Test
import kotlin.test.assertEquals

/** The one-shot Keychain → App-Group migration of the event-album map (capability `event-album`). */
class AlbumMapMigrationTest {

    private val legacyMap = """{"e1":"album-1"}"""
    private val migratedMap = """{"e1":"album-1","e2":"album-2"}"""

    @Test
    fun `a legacy keychain map is migrated`() {
        val source = albumMapSource(stored = null, legacy = SecureStoreRead.Found(legacyMap, StoredProtection.RESTRICTED))

        assertEquals(AlbumMapSource.Migrate(legacyMap), source)
    }

    @Test
    fun `once migrated the app group wins and the keychain is never consulted again`() {
        // Even if a legacy item somehow still existed, an App-Group value takes precedence — so a second
        // read cannot re-migrate (and cannot resurrect a stale map over a newer one).
        val source = albumMapSource(stored = migratedMap, legacy = SecureStoreRead.Found(legacyMap, StoredProtection.RESTRICTED))

        assertEquals(AlbumMapSource.Current(migratedMap), source)
    }

    @Test
    fun `a fresh install has nothing anywhere`() {
        assertEquals(AlbumMapSource.Current(null), albumMapSource(stored = null, legacy = SecureStoreRead.Absent))
    }

    // An unreadable legacy item must not be mistaken for "no album map": concluding the map is empty
    // would silently import a foreign photo to the camera roll only, permanently (the import is one-shot),
    // and deleting the item would lose the mapping for good.
    @Test
    fun `an unreadable legacy item defers rather than deleting or emptying`() {
        val source = albumMapSource(stored = null, legacy = SecureStoreRead.Unavailable("OSStatus -25308"))

        assertEquals(AlbumMapSource.Retry, source)
    }
}
