package app.snapsync.services.album

import app.snapsync.mock.inMemoryPreferences
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.PrefRead
import app.snapsync.ports.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * REPRODUCTION (branch `bug-pending-leaves-clobber`, not a fix): the album map's two read-modify-writes — [AlbumMapService.markFilled]
 * and [AlbumMapService.put] — over a key that answers [PrefRead.Unavailable] while a write still lands. These assert
 * the INTENDED outcome and fail today. No shipped Preferences adapter answers Unavailable with a write that lands
 * (see the report); [ReadFailing] forces it.
 */
class AlbumMapReadFailureTest {

    private class ReadFailing(private val inner: Preferences) : Preferences by inner {
        var failReads = false
        override fun get(key: String): PrefRead = if (failReads) PrefRead.Unavailable("forced") else inner.get(key)
    }

    private val values = mutableMapOf<String, String>()
    private val prefs = ReadFailing(inMemoryPreferences(values))
    private fun service() = AlbumMapService(prefs, inMemorySecureStore())

    @Test
    fun `marking an album filled while the marks are unreadable keeps the other events' marks`() {
        service().apply { put("A", "album-a"); put("B", "album-b"); markFilled("A") }

        prefs.failReads = true
        service().markFilled("B")
        prefs.failReads = false

        assertTrue(service().filled("A"), "A's mark survives")
        assertTrue(service().filled("B"))
    }

    @Test
    fun `storing an album while the map is unreadable keeps the other events' albums`() {
        service().put("A", "album-a")

        prefs.failReads = true
        service().put("B", "album-b")
        prefs.failReads = false

        assertEquals("album-a", service().get("A"))
        assertEquals("album-b", service().get("B"))
    }
}
