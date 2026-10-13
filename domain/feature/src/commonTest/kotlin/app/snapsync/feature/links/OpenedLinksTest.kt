package app.snapsync.feature.links

import app.snapsync.feature.links.readmodel.OpenedLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The link the app was opened with, kept until the screen opens it (capability `join-event`): a cold launch's link
 * waits for the screen that is not built yet, a delivery that opens nothing changes nothing, and a newer link offered
 * while the screen opens the older one stays.
 */
class OpenedLinksTest {

    @Test
    fun `a link waits until the screen opens it`() {
        val links = OpenedLinks()
        links.offer("https://snapsync.stho.net/join/1")
        links.offer(null)
        assertEquals("https://snapsync.stho.net/join/1", links.pending.value, "a delivery that opens nothing keeps it")
        links.consume("https://snapsync.stho.net/join/1")
        assertNull(links.pending.value)
    }

    @Test
    fun `a newer link offered meanwhile stays`() {
        val links = OpenedLinks()
        links.offer("https://snapsync.stho.net/join/1")
        links.offer("https://snapsync.stho.net/join/2")
        links.consume("https://snapsync.stho.net/join/1")
        assertEquals("https://snapsync.stho.net/join/2", links.pending.value)
    }
}
