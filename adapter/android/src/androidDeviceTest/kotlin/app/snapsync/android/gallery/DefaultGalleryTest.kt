package app.snapsync.android.gallery

import android.database.sqlite.SQLiteDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The member's default gallery on Android: `DCIM` and every folder under it. The selection fragments run as written
 * against SQLite's own `LIKE` — the matcher MediaStore applies them with — over one row per path.
 */
class DefaultGalleryTest {

    private val db = SQLiteDatabase.create(null).apply {
        execSQL("CREATE TABLE files (relative_path TEXT)")
        PATHS.forEach { execSQL("INSERT INTO files (relative_path) VALUES (?)", arrayOf(it)) }
    }

    @AfterTest
    fun close() = db.close()

    private fun matching(selection: String): Set<String?> =
        db.rawQuery("SELECT relative_path FROM files WHERE $selection", null).use { c ->
            buildSet { while (c.moveToNext()) add(if (c.isNull(0)) null else c.getString(0)) }
        }

    @Test
    fun `DCIM and every folder under it are the default gallery`() {
        val library = matching(DefaultGallery.SQL)
        listOf("DCIM/Camera/", "DCIM/100ANDRO/", "DCIM/OpenCamera/2026/", "DCIM/").forEach {
            assertTrue(it in library, "$it is in the default gallery")
        }
        assertTrue("dcim/camera/" in library, "case does not matter, as it does not to LIKE")
        assertTrue("DCIM/SnapSync/Party/" in library, "an event album is in the library")
    }

    @Test
    fun `anything outside DCIM is not`() {
        val library = matching(DefaultGallery.SQL)
        listOf("Pictures/Screenshots/", "Pictures/WhatsApp/", "Download/", "DCIMX/", "", null).forEach {
            assertFalse(it in library, "$it is outside the default gallery")
        }
    }

    @Test
    fun `the candidates are every DCIM folder but the event albums`() {
        assertEquals(
            setOf("DCIM/Camera/", "DCIM/100ANDRO/", "DCIM/OpenCamera/2026/", "DCIM/", "dcim/camera/", "DCIM/SnapSyncX/"),
            matching(DefaultGallery.CANDIDATE_SQL),
            "an event album is never a candidate, matched ignoring case; a lookalike of the album root still is",
        )
    }

    @Test
    fun `the event album folder is recognised ignoring case`() {
        assertTrue(DefaultGallery.isAlbumFolder("DCIM/SnapSync/Party (2)/"))
        assertTrue(DefaultGallery.isAlbumFolder("dcim/snapsync/party/"))
        assertFalse(DefaultGallery.isAlbumFolder("DCIM/SnapSyncX/"))
        assertFalse(DefaultGallery.isAlbumFolder(null))
    }

    private companion object {
        val PATHS = listOf(
            "DCIM/Camera/", "DCIM/100ANDRO/", "DCIM/OpenCamera/2026/", "DCIM/", "dcim/camera/", "DCIM/SnapSyncX/",
            "DCIM/SnapSync/Party/", "dcim/snapsync/party/",
            "Pictures/Screenshots/", "Pictures/WhatsApp/", "Pictures/SnapSync/Party/", "Download/", "DCIMX/", "", null,
        )
    }
}
