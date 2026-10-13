package app.snapsync.services.album

import app.snapsync.mock.DatabasesMock
import app.snapsync.services.databases.sharedOver
import app.snapsync.testids.testEventId
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
        assertNull(service().get(testEventId("E")))
        assertFalse(service().filled(testEventId("E")))
    }

    @Test
    fun `an album is unfilled until marked — and a new album for the event starts unfilled`() {
        val service = service()
        service.put(testEventId("E"), "album-1")
        assertFalse(service.filled(testEventId("E")))

        service.markFilled(testEventId("E"))
        assertTrue(service.filled(testEventId("E")))

        service.put(testEventId("E"), "album-2")
        assertEquals("album-2", service.get(testEventId("E")))
        assertFalse(service.filled(testEventId("E")), "a new album has held nothing yet")
    }

    @Test
    fun `a put replaces that event's album and keeps every other event's`() {
        service().apply {
            put(testEventId("E1"), "album-1")
            put(testEventId("E2"), "album-2")
            markFilled(testEventId("E2"))
            put(testEventId("E1"), "album-1b")
        }
        val other = service()
        assertEquals("album-1b", other.get(testEventId("E1")))
        assertEquals("album-2", other.get(testEventId("E2")))
        assertTrue(other.filled(testEventId("E2")), "another event's mark is untouched")
    }

    @Test
    fun `a mark for an event with no album marks nothing`() {
        service().markFilled(testEventId("E"))
        assertFalse(service().filled(testEventId("E")))
    }

    @Test
    fun `a locked database places nothing - remembers nothing - raises nothing`() {
        service().put(testEventId("E"), "album-1")
        databases.operator.lock()
        val service = service()

        assertNull(service.get(testEventId("E")), "unreadable: ensure-then-remember again, never a lost photo")
        assertFalse(
            service.filled(testEventId("E")),
            "unreadable reads as not filled: the album is used, never read as deleted",
        )
        service.put(testEventId("E"), "album-2")
        service.markFilled(testEventId("E"))

        databases.operator.lock(false)
        assertEquals("album-1", service().get(testEventId("E")))
        assertFalse(service().filled(testEventId("E")))
    }
}
