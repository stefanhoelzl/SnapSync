package app.snapsync.android.gallery

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The member's default gallery on Android: `DCIM` and every folder under it, matched as SQLite's `LIKE` matches. */
class DefaultGalleryTest {

    @Test
    fun `DCIM and every folder under it are the default gallery`() {
        assertTrue(DefaultGallery.contains("DCIM/Camera/"))
        assertTrue(DefaultGallery.contains("DCIM/100ANDRO/"))
        assertTrue(DefaultGallery.contains("DCIM/OpenCamera/2026/"))
        assertTrue(DefaultGallery.contains("DCIM/"))
    }

    @Test
    fun `case does not matter as it does not to LIKE`() {
        assertTrue(DefaultGallery.contains("dcim/camera/"))
    }

    @Test
    fun `anything outside DCIM is not`() {
        assertFalse(DefaultGallery.contains("Pictures/Screenshots/"))
        assertFalse(DefaultGallery.contains("Pictures/WhatsApp/"))
        assertFalse(DefaultGallery.contains("Download/"))
        assertFalse(DefaultGallery.contains("DCIMX/"))
        assertFalse(DefaultGallery.contains(""))
        assertFalse(DefaultGallery.contains(null))
    }

    @Test
    fun `an event album is in the library but never a candidate to share`() {
        assertTrue(DefaultGallery.contains("DCIM/SnapSync/Party/"))
        assertFalse(DefaultGallery.isCandidate("DCIM/SnapSync/Party/"))
        assertFalse(DefaultGallery.isCandidate("dcim/snapsync/party/"), "matched ignoring case, as LIKE is")
        assertTrue(DefaultGallery.isAlbumFolder("DCIM/SnapSync/Party (2)/"))
    }

    @Test
    fun `every other DCIM folder is a candidate`() {
        assertTrue(DefaultGallery.isCandidate("DCIM/Camera/"))
        assertTrue(DefaultGallery.isCandidate("DCIM/SnapSyncX/"), "only the album root is left out, not a lookalike")
        assertFalse(DefaultGallery.isCandidate("Pictures/SnapSync/Party/"))
        assertFalse(DefaultGallery.isCandidate(null))
    }
}
