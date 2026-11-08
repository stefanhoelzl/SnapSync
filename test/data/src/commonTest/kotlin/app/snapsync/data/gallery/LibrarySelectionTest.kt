package app.snapsync.data.gallery

import app.snapsync.data.sharing.BeforePublish
import app.snapsync.data.sharing.SelectionSnapshots
import app.snapsync.data.sharing.SelectionState
import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.mock.PhotoLibraryMock
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The library's selection and grant as the starts read them: the observer opened, its snapshots published, the grant read. */
class LibrarySelectionTest {

    @Test
    fun `opening the observer publishes its snapshot after the hook saw it, and the grant reads as the platform's`() = runTest {
        val library = PhotoLibraryMock(access = LibraryGrant.LIMITED)
        val read = LibrarySelection(library.port())
        val snapshots = SelectionSnapshots()
        var seen = 0
        val hook = object : BeforePublish {
            override suspend fun before(selection: SelectionState.Read) {
                seen++
            }
        }
        read.open()
        library.operator.changeSelection(emptyList())
        read.published(snapshots, hook).first()

        assertTrue(library.operator.library().observing, "the observer is open")
        assertEquals(1, seen)
        assertEquals(PhotoAccess.LIMITED, read.access())
    }
}
