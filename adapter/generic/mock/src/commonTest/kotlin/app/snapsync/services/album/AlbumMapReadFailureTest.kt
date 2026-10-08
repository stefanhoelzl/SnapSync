package app.snapsync.services.album

import app.snapsync.mock.inMemoryPreferences
import app.snapsync.model.PrefRead
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The album map's read-modify-writes — [AlbumMapService.markFilled] and [AlbumMapService.put] — never write over what
 * they could not read: a key that answers [PrefRead.Unavailable] while a write still lands. [ReadFailing] forces it,
 * which no shipped Preferences adapter answers today.
 */
class AlbumMapReadFailureTest {

    private class ReadFailing(private val inner: Preferences) : Preferences by inner {
        var failReads = false

        /** Only these keys fail, when [failReads] is off; a write to one of [refuseWrites] is refused. */
        var failKeys = emptySet<String>()
        var refuseWrites = emptySet<String>()

        override fun get(key: String): PrefRead =
            if (failReads || key in failKeys) PrefRead.Unavailable("forced") else inner.get(key)

        override fun set(key: String, value: String): WriteOutcome =
            if (key in refuseWrites) WriteOutcome.Failed("forced") else inner.set(key, value)
    }

    private val values = mutableMapOf<String, String>()
    private val prefs = ReadFailing(inMemoryPreferences(values))
    private fun service() = AlbumMapService(prefs)

    @Test
    fun `marking an album filled while the marks are unreadable keeps the other events' marks`() {
        service().apply {
            put("A", "album-a")
            put("B", "album-b")
            markFilled("A")
        }

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

    @Test
    fun `unreadable filled marks read as unfilled - and a new album leaves them as they were`() {
        service().apply {
            put("A", "album-a")
            markFilled("A")
        }

        prefs.failKeys = setOf(ALBUM_FILLED_KEY)
        assertFalse(service().filled("A"), "a mark that cannot be read places nothing as filled")
        service().put("A", "album-a2")
        prefs.failKeys = emptySet()

        assertEquals("album-a2", service().get("A"), "the album itself is stored")
        assertTrue(service().filled("A"), "the marks it could not read were not rewritten")
    }

    @Test
    fun `a filled mark that cannot be stored is not believed`() {
        service().put("A", "album-a")

        prefs.refuseWrites = setOf(ALBUM_FILLED_KEY)
        service().markFilled("A")

        assertFalse(service().filled("A"))
    }
}
