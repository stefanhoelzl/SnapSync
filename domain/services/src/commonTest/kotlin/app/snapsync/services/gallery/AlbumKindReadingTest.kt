package app.snapsync.services.gallery

import app.snapsync.model.AlbumKind
import app.snapsync.ports.LibraryAlbumKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** How the platform's way of holding an album reads in the app ([albumKind], [GalleryAlbums.kind]). */
class AlbumKindReadingTest {

    @Test
    fun `each platform album kind reads as the app's kind of the same name`() {
        for (kind in LibraryAlbumKind.entries) assertEquals(kind.name, kind.albumKind().name)
    }

    @Test
    fun `the album service reads the library's own kind`() {
        val folders = object : app.snapsync.ports.GalleryReader by GrantOnlyReader({ error("not used") }) {
            override val albumKind = LibraryAlbumKind.FOLDER
        }
        assertEquals(AlbumKind.FOLDER, GalleryAlbums(folders).kind)
    }
}
