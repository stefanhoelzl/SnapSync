package app.snapsync.services.platform

import app.snapsync.mock.PhotoLibraryMock
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The member's asks of the photo grant: the platform's question, and its picker for a partial grant's selection. */
class PhotoAccessAsksTest {

    @Test
    fun `asking puts the platform's question, and the grant follows its answer`() = runTest {
        val library = PhotoLibraryMock(access = LibraryGrant.NOT_DETERMINED, requestAnswer = LibraryGrant.LIMITED)
        val gallery = library.port()
        PhotoAccessAsks(gallery).ask()
        assertEquals(LibraryGrant.LIMITED, gallery.access())
    }

    @Test
    fun `revising a partial grant shows the platform's picker`() = runTest {
        val library = PhotoLibraryMock(access = LibraryGrant.LIMITED)
        PhotoAccessAsks(library.port()).widen()
        assertEquals(1, library.pickersShown)
    }
}
