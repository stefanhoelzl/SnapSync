package app.snapsync.services.album

import app.snapsync.fake.inMemoryPreferences
import app.snapsync.model.PrefRead
import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureSlots
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.StoredProtection
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.Preferences
import app.snapsync.ports.SecureStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The event-album map service's one-shot Keychain migration (capability `event-album`): moved once, then the legacy
 * item deleted — never deleted before the move is stored, never concluded empty while the legacy item is unreadable.
 */
class AlbumMapServiceTest {

    /** The secure store holding only the legacy map's slot; any other slot is a test failure. */
    private class Legacy(var read: SecureStoreRead) : SecureStore {
        var deleted = false
        override fun read(slot: SecureSlot) = read.also { check(slot == SecureSlots.ALBUM_MAP_LEGACY) }
        override fun write(slot: SecureSlot, value: String) = error("the service never writes the legacy item")
        override fun migrateProtection(slot: SecureSlot) = WriteOutcome.Ok
        override fun delete(slot: SecureSlot): WriteOutcome {
            check(slot == SecureSlots.ALBUM_MAP_LEGACY)
            deleted = true
            read = SecureStoreRead.Absent
            return WriteOutcome.Ok
        }
    }

    private val legacyMap = SecureStoreRead.Found("""{"E":"album-1"}""", StoredProtection.BACKGROUND_READABLE)

    @Test
    fun `a legacy map is migrated once and then deleted`() {
        val values = mutableMapOf<String, String>()
        val legacy = Legacy(legacyMap)
        val service = AlbumMapService(inMemoryPreferences(values), legacy)

        assertEquals("album-1", service.get("E"))
        assertTrue(legacy.deleted, "no stale item outlives the migration")
        assertEquals("""{"E":"album-1"}""", values[ALBUM_MAP_KEY])
    }

    @Test
    fun `a legacy map is not deleted when its move could not be stored`() {
        val refusing = object : Preferences {
            override fun get(key: String) = PrefRead.Absent
            override fun set(key: String, value: String) = WriteOutcome.Failed("full")
            override fun remove(key: String) = WriteOutcome.Ok
        }
        val legacy = Legacy(legacyMap)

        assertEquals("album-1", AlbumMapService(refusing, legacy).get("E"), "this read still places the photo")
        assertFalse(legacy.deleted, "deleting it would lose the map for good")
    }

    @Test
    fun `an unreadable legacy map is neither deleted nor read as empty for good`() {
        val legacy = Legacy(SecureStoreRead.Unavailable("locked"))
        val service = AlbumMapService(inMemoryPreferences(), legacy)

        assertNull(service.get("E"), "no placement this pass")
        assertFalse(legacy.deleted)
        legacy.read = legacyMap
        assertEquals("album-1", service.get("E"), "the next readable pass migrates it")
    }

    @Test
    fun `the preferences win and the legacy item is never read again`() {
        val legacy = Legacy(SecureStoreRead.Unavailable("must not be asked"))
        val service = AlbumMapService(inMemoryPreferences(mutableMapOf(ALBUM_MAP_KEY to """{"E":"album-2"}""")), legacy)

        assertEquals("album-2", service.get("E"))
        service.put("F", "album-3")
        assertEquals("album-3", service.get("F"))
        assertEquals("album-2", service.get("E"), "a put keeps the other entries")
    }

    @Test
    fun `unreadable preferences place nothing and migrate nothing`() {
        val unreadable = object : Preferences {
            override fun get(key: String) = PrefRead.Unavailable("suite unavailable")
            override fun set(key: String, value: String) = WriteOutcome.Failed("suite unavailable")
            override fun remove(key: String) = WriteOutcome.Failed("suite unavailable")
        }
        val legacy = Legacy(legacyMap)

        assertNull(AlbumMapService(unreadable, legacy).get("E"))
        assertFalse(legacy.deleted, "a map that may exist is not migrated over")
    }
}
