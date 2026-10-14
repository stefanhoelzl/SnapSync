package app.snapsync.mock

import app.snapsync.ports.WriteOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The operator's rename of an iPhone album: the title changes, and the album the app knows still takes its photos. */
class PhotoLibraryRenameTest {

    @Test
    fun a_renamed_collection_album_keeps_its_id_and_still_takes_photos() = runTest {
        val library = PhotoLibraryMock()
        val gallery = library.port()
        library.operator.add("A")
        val album = assertNotNull(gallery.createAlbum("Party"))

        library.operator.renameAlbum(album.value, "Best party")

        assertEquals(listOf("Best party"), library.operator.albums().map { it.title })
        assertEquals(WriteOutcome.Ok, gallery.addToAlbum(album, setOf(library.contents.value.single().id)))
        assertEquals(listOf("A"), library.operator.albums().single().photos, "the photo is in the renamed album")
    }
}
