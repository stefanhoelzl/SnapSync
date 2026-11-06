package app.snapsync.application.sharing

import app.snapsync.application.EntryPointCall
import app.snapsync.data.membership.EVENT
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.enrolled
import app.snapsync.data.membership.event
import app.snapsync.data.membership.share
import app.snapsync.data.membership.sharedOver
import app.snapsync.data.receiving.RecognitionRead
import app.snapsync.data.sharing.AdmittedPhotos
import app.snapsync.data.sharing.DURING
import app.snapsync.data.sharing.Library
import app.snapsync.data.sharing.ids
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.receiving.MintsRecognised
import app.snapsync.domain.receiving.Recognised
import app.snapsync.domain.sharing.Direction
import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.LibraryAssets
import app.snapsync.ports.LibraryGrant
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The status total `N` (capability `sync-status`): the member's own photos the membership admits, counted by the same
 * read the upload takes — and never a count not taken: nothing without a membership, nothing before the received photos
 * are recognised, and a library that cannot be read keeps what was last counted.
 */
@OptIn(EntryPointCall::class, MintsRecognised::class)
class CountOwnPhotosTest {

    private val library = Library(LibraryAssets.photo("A", DURING), LibraryAssets.photo("B", DURING))
    private val databases = DatabasesMock()
    private val membership = MembershipRepository(sharedOver(databases))
    private val admitted = AdmittedPhotos()
    private val count = CountOwnPhotos(membership, library.received, library.own, admitted)

    private fun joinedAndRecognised() {
        membership.enter(enrolled())
        library.received.recognition = RecognitionRead.Recognised(Recognised(testEventId(EVENT)))
    }

    @Test
    fun `before any count the total is not counted - never a zero`() {
        assertNull(admitted.state.value)
    }

    @Test
    fun `no membership counts nothing and never walks`() = runTest {
        assertEquals(OwnPhotosCount.NO_MEMBERSHIP, count())
        assertNull(admitted.state.value)
        assertEquals(0, library.walks)
    }

    @Test
    fun `a membership that cannot be read counts nothing`() = runTest {
        databases.lock()

        assertEquals(OwnPhotosCount.NO_MEMBERSHIP, count())
        assertNull(admitted.state.value)
        assertEquals(0, library.walks)
    }

    @Test
    fun `a membership that shares nothing counts its zero at once - nothing read, no recognition awaited`() = runTest {
        membership.enter(enrolled(event().let { Membership.Active(it, share(it, direction = Direction.DownloadOnly)) }))

        assertEquals(OwnPhotosCount.COUNTED, count())
        assertEquals(emptySet(), admitted.state.value)
        assertEquals(0, library.walks)
    }

    @Test
    fun `received photos not recognised yet are no count`() = runTest {
        membership.enter(enrolled())

        assertEquals(OwnPhotosCount.NOT_RECOGNISED, count())
        assertNull(admitted.state.value)
        assertEquals(0, library.walks)
    }

    @Test
    fun `a recognition record that cannot be read is no count`() = runTest {
        membership.enter(enrolled())
        library.received.recognition = RecognitionRead.Unavailable("the download store is locked")

        assertEquals(OwnPhotosCount.NOT_RECOGNISED, count())
        assertNull(admitted.state.value)
    }

    @Test
    fun `a count holds the admitted photos themselves`() = runTest {
        joinedAndRecognised()

        assertEquals(OwnPhotosCount.COUNTED, count())
        assertEquals(ids("A", "B").toSet(), admitted.state.value)
    }

    @Test
    fun `a library that cannot be read keeps the last count and withdraws none`() = runTest {
        joinedAndRecognised()
        count()

        library.grant.value = LibraryGrant.DENIED
        library.photos.value = emptyList()
        assertEquals(OwnPhotosCount.NOT_READABLE, count())
        assertEquals(ids("A", "B").toSet(), admitted.state.value)
    }

    @Test
    fun `a library that cannot be read before any count stays not counted`() = runTest {
        joinedAndRecognised()
        library.grant.value = LibraryGrant.DENIED

        assertEquals(OwnPhotosCount.NOT_READABLE, count())
        assertNull(admitted.state.value)
    }

    @Test
    fun `a later count follows the library`() = runTest {
        joinedAndRecognised()
        count()

        library.photos.value = listOf(LibraryAssets.photo("A", DURING))
        count()
        assertEquals(ids("A").toSet(), admitted.state.value)
    }
}
