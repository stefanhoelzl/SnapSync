package app.snapsync.services.album

import app.snapsync.mock.inMemoryPreferences
import app.snapsync.model.PrefRead
import app.snapsync.ports.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The album map's read-modify-writes — [AlbumMapService.markFilled] and [AlbumMapService.put] — never write over what
 * they could not read: a key that answers [PrefRead.Unavailable] while a write still lands. [ReadFailing] forces it,
 * which no shipped Preferences adapter answers today.
 */
class AlbumMapReadFailureTest {

    private class ReadFailing(private val inner: Preferences) : Preferences by inner {
        var failReads = false
        override fun get(key: String): PrefRead = if (failReads) PrefRead.Unavailable("forced") else inner.get(key)
    }

    private val values = mutableMapOf<String, String>()
    private val prefs = ReadFailing(inMemoryPreferences(values))
    private fun service() = AlbumMapService(prefs)

    @Test
    fun `marking an album filled while the marks are unreadable keeps the other events' marks`() {
        service().apply { put("A", "album-a"); put("B", "album-b"); markFilled("A") }

        prefs.failReads = true
        service().markFilled("B")
        prefs.failReads = false

        assertTrue(service().filled("A"), "A's mark survives")
        service().markFilled("B")
        assertTrue(service().filled("B"))
    }

    @Test
    fun `storing an album while the map is unreadable keeps the other events' albums`() {
        service().put("A", "album-a")

        prefs.failReads = true
        service().put("B", "album-b")
        prefs.failReads = false

        assertEquals("album-a", service().get("A"))
        service().put("B", "album-b")
        assertEquals("album-b", service().get("B"))
    }
}
