package app.snapsync.domain.push

import app.snapsync.domain.event.EventId
import kotlin.test.Test
import kotlin.test.assertEquals

/** When a registration is published, and when a silent push's wake joins the tail. */
class PushVerdictsTest {

    @Test
    fun `a delivery publishes only a registration other than the last one accepted`() {
        assertEquals(PushRegistrationVerdict.UNCHANGED, of(PushTrigger.DELIVERED, LastRegistration.Recorded(KEY)))
        assertEquals(PushRegistrationVerdict.PUBLISH, of(PushTrigger.DELIVERED, LastRegistration.Recorded(OTHER_KEY)))
        assertEquals(PushRegistrationVerdict.PUBLISH, of(PushTrigger.DELIVERED, LastRegistration.None))
        assertEquals(PushRegistrationVerdict.PUBLISH, of(PushTrigger.DELIVERED, LastRegistration.Unavailable))
    }

    @Test
    fun `a new credential and a join publish whatever the record holds`() {
        for (trigger in listOf(PushTrigger.CREDENTIAL, PushTrigger.JOINED)) {
            assertEquals(PushRegistrationVerdict.PUBLISH, of(trigger, LastRegistration.Recorded(KEY)), "$trigger")
        }
    }

    @Test
    fun `a push joins the tail only for the event this device is a member of`() {
        assertEquals(PushTailVerdict.Joins, PushTailVerdict.of(EVENT, MemberOf.Event(EVENT)))
        assertEquals(PushTailVerdict.OtherEvent, PushTailVerdict.of(EVENT, MemberOf.Event(OTHER)))
        // A locally-left event: the backend still pushes it, and this device holds no membership of it.
        assertEquals(PushTailVerdict.OtherEvent, PushTailVerdict.of(EVENT, MemberOf.Nothing))
        assertEquals(PushTailVerdict.MembershipUnreadable, PushTailVerdict.of(EVENT, MemberOf.Unreadable))
        assertEquals(PushTailVerdict.NamesNoEvent, PushTailVerdict.of(null, MemberOf.Event(EVENT)))
    }

    private fun of(trigger: PushTrigger, last: LastRegistration) = PushRegistrationVerdict.of(trigger, KEY, last)

    private companion object {
        val KEY = PushRegistrationKey("apns\nsandbox\nD\nT1")
        val OTHER_KEY = PushRegistrationKey("apns\nsandbox\nD\nT2")
        val EVENT = EventId.parse("0f0e8a5c-3f1b-4c2d-9e7a-1b2c3d4e5f60")!!
        val OTHER = EventId.parse("1f0e8a5c-3f1b-4c2d-9e7a-1b2c3d4e5f61")!!
    }
}
