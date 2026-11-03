package app.snapsync.query.push

import app.snapsync.application.EntryPointCall
import app.snapsync.application.membership.Device
import app.snapsync.application.membership.EnterMembership
import app.snapsync.application.membership.LeaveEvent
import app.snapsync.data.membership.EVENT
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.OTHER
import app.snapsync.data.membership.enrolled
import app.snapsync.domain.push.PushTailVerdict
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Whether a silent push's wake joins the tail, over the membership as the push's entry point re-read it. */
@OptIn(EntryPointCall::class)
class PushTailLookupTest {

    private val device = Device()
    private val tail = PushTailLookup(device.membership)

    @Test
    fun `a membership that cannot be read joins no push — it is not no event`() = runTest {
        EnterMembership(device.membership)(enrolled())
        device.databases.lock()
        // A process started on the locked phone: nothing conclusive read yet.
        val locked = PushTailLookup(MembershipRepository(device.shared))
        assertEquals(PushTailVerdict.MembershipUnreadable, locked.of(testEventId(EVENT)))
    }

    @Test
    fun `only a push for the event held joins the tail`() = runTest {
        EnterMembership(device.membership)(enrolled())
        assertEquals(PushTailVerdict.Joins, tail.of(testEventId(EVENT)))
        assertEquals(PushTailVerdict.OtherEvent, tail.of(testEventId(OTHER)))
        assertEquals(PushTailVerdict.NamesNoEvent, tail.of(null))
    }

    @Test
    fun `a push for a locally-left event joins nothing — the backend still counts this device a member`() = runTest {
        EnterMembership(device.membership)(enrolled())
        LeaveEvent(device.membership)()
        assertEquals(PushTailVerdict.OtherEvent, tail.of(testEventId(EVENT)))
    }
}
