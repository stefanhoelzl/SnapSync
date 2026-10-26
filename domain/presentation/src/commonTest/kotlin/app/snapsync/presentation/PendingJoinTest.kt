package app.snapsync.presentation

import app.snapsync.domain.event.EventKey
import app.snapsync.testids.testEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A pending join reaches logs and test failures, and an encrypted event's link key is the event's secret — so its
 * written form says whether a key is present and never what it is.
 */
@OptIn(app.snapsync.domain.event.MakesEventKey::class)
class PendingJoinTest {

    @Test
    fun `a pending join's written form says a key is present without carrying it`() {
        val pending = PendingJoin(
            testEventId("event-1"),
            JoinProgress.Loading,
            key = EventKey.of(ByteArray(EventKey.LENGTH) { 9 }),
        )
        val written = pending.toString()

        assertFalse("9, 9" in written, "the key leaked into $written")
        assertEquals("PendingJoin(eventId=${testEventId("event-1")}, phase=Loading, key=present)", written)
    }

    @Test
    fun `a pending join with no key says so`() {
        assertEquals(
            "PendingJoin(eventId=${testEventId("event-1")}, phase=Loading, key=null)",
            PendingJoin(testEventId("event-1"), JoinProgress.Loading).toString(),
        )
    }
}
