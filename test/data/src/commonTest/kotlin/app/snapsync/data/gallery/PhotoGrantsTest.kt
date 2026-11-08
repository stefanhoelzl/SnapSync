@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.data.gallery

import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.ports.LibraryGrant
import app.snapsync.query.sharing.PhotoGrantQuery
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The platform's grant, in the domain's words, followed as it changes. */
class PhotoGrantsTest {

    @Test
    fun `each grant the platform reports is read as the domain's`() {
        assertEquals(
            listOf(PhotoGrant.UNDECIDED, PhotoGrant.REFUSED, PhotoGrant.LIMITED, PhotoGrant.FULL),
            LibraryGrant.entries.map(PhotoGrants::of),
        )
    }

    @Test
    fun `the grant is followed as it changes`() = runTest {
        val source = MutableStateFlow(LibraryGrant.NOT_DETERMINED)
        val grant = PhotoGrantQuery(PhotoGrants(source)).state
        assertEquals(PhotoGrant.UNDECIDED, grant.value)
        assertEquals(listOf(PhotoGrant.UNDECIDED), grant.replayCache)
        source.value = LibraryGrant.GRANTED
        assertEquals(PhotoGrant.FULL, grant.first())
    }

    @Test
    fun `the value the launch reconciles against is no change, and a change landing meanwhile is one`() = runTest {
        val source = MutableStateFlow(LibraryGrant.NOT_DETERMINED)
        val changes = PhotoGrantQuery(PhotoGrants(source)).changesSinceLaunch()
        // Lands while the launch reconciles, before anything follows the changes.
        source.value = LibraryGrant.GRANTED
        assertEquals(listOf(PhotoGrant.FULL), changes.take(1).toList())
    }

    @Test
    fun `the launch's own value, still held, is dropped until the grant really changes`() = runTest {
        val source = MutableStateFlow(LibraryGrant.NOT_DETERMINED)
        val changes = PhotoGrantQuery(PhotoGrants(source)).changesSinceLaunch()
        val seen = async { changes.take(1).toList() }
        runCurrent()
        source.value = LibraryGrant.DENIED
        assertEquals(listOf(PhotoGrant.REFUSED), seen.await())
    }
}
