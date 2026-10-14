package app.snapsync.services.gallery

import app.snapsync.model.AlbumContents
import app.snapsync.ports.LibraryAlbumKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the platform's way of holding an album means for the event album ([contents], [GalleryAlbums.contents]). */
class AlbumContentsReadingTest {

    @Test
    fun `a collection album holds shared and received photos, a folder album received ones only`() {
        assertEquals(
            listOf(AlbumContents.SHARED_AND_RECEIVED, AlbumContents.RECEIVED),
            listOf(LibraryAlbumKind.COLLECTION, LibraryAlbumKind.FOLDER).map { it.contents() },
        )
    }

    @Test
    fun `the album service reads the library's own kind`() {
        val folders = object : app.snapsync.ports.GalleryReader by GrantOnlyReader({ error("not used") }) {
            override val albumKind = LibraryAlbumKind.FOLDER
        }
        assertEquals(AlbumContents.RECEIVED, GalleryAlbums(folders).contents)
    }
}
