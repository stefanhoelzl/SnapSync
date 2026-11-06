package app.snapsync.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals

/** The grant as the screen reads it: photos are read under a partial grant and a full one, never under the others. */
class PhotoGrantTest {
    @Test
    fun `photos are read under a partial grant and a full one`() {
        assertEquals(listOf(PhotoGrant.LIMITED, PhotoGrant.FULL), PhotoGrant.entries.filter { it.readsPhotos })
    }
}
