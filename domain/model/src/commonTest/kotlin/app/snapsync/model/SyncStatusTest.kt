package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The status the screen still reads in the old zones' words, and the grant's "may photos be read" (`sync-status`). */
class SyncStatusTest {

    @Test
    fun `a status is loading until it carries what was read`() {
        val progress = SyncProgress(
            pending = 1,
            completed = 2,
            total = 3,
            failed = 0,
            active = true,
            estimatedRemaining = null,
        )
        assertIs<SyncStatus.Loading>(SyncStatus.Loading)
        assertEquals(progress, assertIs<SyncStatus.Ready>(SyncStatus.Ready(progress)).progress)
    }

    @Test
    fun `a full and a partial grant may read photos, an undecided or refused one may not`() {
        assertTrue(GalleryAccess.GRANTED.grantsPhotoAccess)
        assertTrue(GalleryAccess.LIMITED.grantsPhotoAccess)
        assertFalse(GalleryAccess.DENIED.grantsPhotoAccess)
        assertFalse(GalleryAccess.NOT_DETERMINED.grantsPhotoAccess)
    }
}
