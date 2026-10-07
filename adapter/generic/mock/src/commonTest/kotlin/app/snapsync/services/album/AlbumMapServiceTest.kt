package app.snapsync.services.album

import app.snapsync.mock.inMemoryPreferences
import app.snapsync.model.PrefRead
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The event-album map service (capability `event-album`): the map and the filled marks over the shared preferences. */
class AlbumMapServiceTest {

    @Test
    fun `an album is unfilled until marked — and a new album for the event starts unfilled`() {
        val values = mutableMapOf(ALBUM_MAP_KEY to """{"E":"album-1"}""")
        val service = AlbumMapService(inMemoryPreferences(values))

        assertFalse(service.filled("E"), "a map stored before the mark existed reads as unfilled")
        service.markFilled("E")
        assertTrue(AlbumMapService(inMemoryPreferences(values)).filled("E"), "the mark persists")
        assertFalse(service.filled("F"), "the mark is per event")

        service.put("E", "album-2")
        assertFalse(service.filled("E"), "a recreated album has held nothing yet")
        assertEquals("album-2", service.get("E"))
    }

    @Test
    fun `a put keeps the other entries`() {
        val service = AlbumMapService(inMemoryPreferences(mutableMapOf(ALBUM_MAP_KEY to """{"E":"album-2"}""")))

        assertEquals("album-2", service.get("E"))
        service.put("F", "album-3")
        assertEquals("album-3", service.get("F"))
        assertEquals("album-2", service.get("E"))
    }

    @Test
    fun `nothing stored reads as no album`() {
        assertNull(AlbumMapService(inMemoryPreferences()).get("E"))
    }

    @Test
    fun `unreadable preferences place nothing`() {
        val unreadable = object : Preferences {
            override fun get(key: String) = PrefRead.Unavailable("suite unavailable")
            override fun set(key: String, value: String) = WriteOutcome.Failed("suite unavailable")
            override fun remove(key: String) = WriteOutcome.Failed("suite unavailable")
        }

        assertNull(AlbumMapService(unreadable).get("E"))
        assertFalse(AlbumMapService(unreadable).filled("E"), "an unreadable store reads as not filled")
    }

    @Test
    fun `a value that does not decode reads as empty`() {
        val values = mutableMapOf(ALBUM_MAP_KEY to "{not json", ALBUM_FILLED_KEY to "{not json")
        val service = AlbumMapService(inMemoryPreferences(values))

        assertNull(service.get("E"))
        assertFalse(service.filled("E"))
    }

    @Test
    fun `a refused write remembers nothing and does not throw`() {
        val refusing = object : Preferences {
            override fun get(key: String) = PrefRead.Absent
            override fun set(key: String, value: String) = WriteOutcome.Failed("full")
            override fun remove(key: String) = WriteOutcome.Ok
        }
        val service = AlbumMapService(refusing)

        service.put("E", "album-1")
        service.markFilled("E")
        assertNull(service.get("E"))
        assertFalse(service.filled("E"))
    }
}
