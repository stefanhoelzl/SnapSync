package app.snapsync.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals

/** The grant: photos are read under a partial grant and a full one, never under the others. */
class PhotoGrantTest {
    @Test
    fun `photos are read under a partial grant and a full one`() {
        assertEquals(listOf(PhotoGrant.LIMITED, PhotoGrant.FULL), PhotoGrant.entries.filter { it.readsPhotos })
    }

    @Test
    fun `a read takes a grant as its access - undecided and refused alike leave nothing to read`() {
        assertEquals(
            listOf(PhotoAccess.NONE, PhotoAccess.NONE, PhotoAccess.LIMITED, PhotoAccess.FULL),
            PhotoGrant.entries.map { it.access },
        )
    }
}
