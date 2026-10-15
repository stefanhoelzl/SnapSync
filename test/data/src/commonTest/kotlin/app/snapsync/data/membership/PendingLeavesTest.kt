package app.snapsync.data.membership

import app.snapsync.data.store.StoreWrite
import app.snapsync.domain.membership.CheckedAt
import app.snapsync.format.TimestampText
import app.snapsync.mock.DatabasesMock
import app.snapsync.testids.testEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The leaves owed to the backend, and the times a wake last read an event. */
class PendingLeavesTest {

    private val device = DatabasesMock()
    private val shared = sharedOver(device)
    private val leaves = PendingLeaves(shared)

    @Test
    fun `a leave is owed until the backend confirms it`() {
        val repository = MembershipRepository(shared)
        repository.enter(enrolled())
        repository.leave()
        assertEquals(PendingLeavesRead.Ok(setOf(testEventId(EVENT))), leaves.outstanding())

        assertEquals(StoreWrite.Written, leaves.confirm(testEventId(EVENT)))
        assertEquals(PendingLeavesRead.Ok(emptySet()), leaves.outstanding())
    }

    @Test
    fun `leaves that cannot be read or confirmed say so`() {
        device.lock()
        assertIs<PendingLeavesRead.Unavailable>(leaves.outstanding())
        assertIs<StoreWrite.Failed>(leaves.confirm(testEventId(EVENT)))
    }

    @Test
    fun `a check is never made until stamped, and unreadable when the store is`() {
        val checks = EventCheckRecord(shared)
        val at = TimestampText.parse("2026-07-22T12:00:00Z")
        assertEquals(CheckedAt.Never, checks.last(EventCheck.PHOTOS, testEventId(EVENT)))
        assertEquals(StoreWrite.Written, checks.stamp(EventCheck.PHOTOS, testEventId(EVENT), at))
        assertEquals(CheckedAt.At(at), checks.last(EventCheck.PHOTOS, testEventId(EVENT)))
        assertEquals(CheckedAt.Never, checks.last(EventCheck.STATE, testEventId(EVENT)), "each kind on its own")

        device.lock()
        assertEquals(CheckedAt.Unreadable, checks.last(EventCheck.PHOTOS, testEventId(EVENT)))
        assertIs<StoreWrite.Failed>(checks.stamp(EventCheck.PHOTOS, testEventId(EVENT), at))
    }
}
