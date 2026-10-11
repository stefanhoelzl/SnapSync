package app.snapsync.services.album

import app.snapsync.mock.DatabasesMock
import app.snapsync.services.databases.sharedOver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The event-album map service (capability `event-album`): one `eventAlbum` row per event in the shared database, its
 * album and whether it has held a photo. Each [service] is a process of its own, as the app and the extension are.
 */
class AlbumMapServiceTest {

    private val databases = DatabasesMock()

    private fun service() = AlbumMapService(sharedOver(databases.port()))

    @Test
    fun `nothing stored reads as no album`() {
        assertNull(service().get("E"))
        assertFalse(service().filled("E"))
    }

    @Test
    fun `an album is unfilled until marked — and a new album for the event starts unfilled`() {
        val service = service()
        service.put("E", "album-1")
        assertFalse(service.filled("E"))

        service.markFilled("E")
        assertTrue(service.filled("E"))

        service.put("E", "album-2")
        assertEquals("album-2", service.get("E"))
        assertFalse(service.filled("E"), "a new album has held nothing yet")
    }

    @Test
    fun `a put replaces that event's album and keeps every other event's`() {
        service().apply {
            put("E1", "album-1")
            put("E2", "album-2")
            markFilled("E2")
            put("E1", "album-1b")
        }
        val other = service()
        assertEquals("album-1b", other.get("E1"))
        assertEquals("album-2", other.get("E2"))
        assertTrue(other.filled("E2"), "another event's mark is untouched")
    }

    @Test
    fun `a mark for an event with no album marks nothing`() {
        service().markFilled("E")
        assertFalse(service().filled("E"))
    }

    @Test
    fun `a locked database places nothing - remembers nothing - raises nothing`() {
        service().put("E", "album-1")
        databases.operator.lock()
        val service = service()

        assertNull(service.get("E"), "unreadable: ensure-then-remember again, never a lost photo")
        assertFalse(service.filled("E"), "unreadable reads as not filled: the album is used, never read as deleted")
        service.put("E", "album-2")
        service.markFilled("E")

        databases.operator.lock(false)
        assertEquals("album-1", service().get("E"))
        assertFalse(service().filled("E"))
    }
}
