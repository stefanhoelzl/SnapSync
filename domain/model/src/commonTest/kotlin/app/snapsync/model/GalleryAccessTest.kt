package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** The grant as the old zones read it: four readings, a full grant the only whole one. */
class GalleryAccessTest {
    @Test
    fun `the grant reads as one of four`() {
        assertEquals(listOf("NOT_DETERMINED", "DENIED", "LIMITED", "GRANTED"), GalleryAccess.entries.map { it.name })
    }
}
