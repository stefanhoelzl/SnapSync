@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.services.gallery

import app.snapsync.model.GalleryAccess
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the grant does after the launch: real changes only, the launch's own value dropped. */
class GalleryAccessStateTest {

    @Test
    fun `the value the launch reconciles against is no change, and a change landing meanwhile is one`() = runTest {
        val source = MutableStateFlow(LibraryGrant.NOT_DETERMINED)
        val state = GalleryAccessState(source)
        val changes = state.changesSinceLaunch()
        // Lands while the launch reconciles, before anything follows the changes.
        source.value = LibraryGrant.GRANTED
        assertEquals(listOf(GalleryAccess.GRANTED), changes.take(1).toList())
    }

    @Test
    fun `the launch's own value, still held, is dropped until the grant really changes`() = runTest {
        val source = MutableStateFlow(LibraryGrant.NOT_DETERMINED)
        val changes = GalleryAccessState(source).changesSinceLaunch()
        val seen = async { changes.take(1).toList() }
        runCurrent()
        source.value = LibraryGrant.DENIED
        assertEquals(listOf(GalleryAccess.DENIED), seen.await())
    }
}
