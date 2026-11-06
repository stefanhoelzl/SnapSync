package app.snapsync.query.membership

import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.active
import app.snapsync.data.membership.enrolled
import app.snapsync.data.membership.sharedOver
import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.Stored
import app.snapsync.data.store.use
import app.snapsync.domain.album.AlbumChoice
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import app.snapsync.format.TimestampText
import app.snapsync.mock.DatabasesMock
import app.snapsync.testids.testEventId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The membership as a screen reads it: the one entered, none while none is, and unreadable while none could be read. */
@OptIn(ExperimentalCoroutinesApi::class)
class MembershipQueryTest {

    private val device = DatabasesMock()
    private val shared = sharedOver(device)
    private val repository = MembershipRepository(shared)
    private val query = MembershipQuery(repository)

    @Test
    fun `the membership held follows the store and tells each change once`() = runTest {
        assertEquals(listOf<MembershipShown>(MembershipShown.None), query.shown.replayCache)
        val seen = mutableListOf<MembershipShown>()
        backgroundScope.launch { query.shown.collect { seen += it } }
        runCurrent()
        repository.reload() // a fresh read that still finds none: nothing new to tell
        runCurrent()
        repository.enter(enrolled())
        runCurrent()
        assertEquals(MembershipShown.Held(active()), query.shown.value)
        assertEquals(listOf(MembershipShown.None, MembershipShown.Held(active())), seen)
    }

    @Test
    fun `a membership that cannot be read is told as unreadable, holding none`() {
        val legacy = MembershipQuery(MembershipRepository(SharedDatabase(device.port(), legacy = null)))
        assertEquals(MembershipShown.Unreadable, legacy.shown.value)
        assertNull(legacy.shown.value.held)
    }

    @Test
    fun `a row this build refuses is none held, the way back being a join`() {
        // A blank name: a row no path of this build writes, which read again reads the same.
        shared.use {
            it.membershipQueries.saveMembership(
                eventId = Stored.Valid(testEventId("11111111-1111-4111-8111-111111111111")),
                name = "  ",
                minPhotoDate = CaptureCutoff(TimestampText.parse(START)),
                startsAt = EventStart(TimestampText.parse(START)),
                endsAt = EventEnd(TimestampText.parse(END)),
                maxPhotoDate = CaptureCeiling(TimestampText.parse(END)),
                deletesAt = DeletesAt(TimestampText.parse("2026-08-21T18:00:00Z")),
                direction = Direction.Both,
                saveToAlbum = AlbumChoice.LibraryOnly,
                membersActive = 1,
                membersSettled = 0,
                membersWaiting = null,
                keyId = null,
                openUntil = null,
                deleted = false,
            )
        }
        assertEquals(MembershipShown.None, query.shown.value)
        assertNull(query.shown.value.held, "none held")
    }

    private companion object {
        const val START = "2026-07-14T18:00:00Z"
        const val END = "2026-07-21T18:00:00Z"
    }
}
