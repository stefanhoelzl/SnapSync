package app.snapsync.presentation

import app.snapsync.testids.testEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A pending join reaches logs and test failures, and an encrypted event's link key is the event's secret — so its
 * written form says whether a key is present and never what it is.
 */
class PendingJoinTest {

    @Test
    fun `a pending join's written form says a key is present without carrying it`() {
        val pending = PendingJoin(
            testEventId("event-1"),
            JoinProgress.Loading,
            linkKey = "the-secret-key",
            eventKeyId = "k1",
        )
        val written = pending.toString()

        assertFalse("the-secret-key" in written, "the key leaked into $written")
        assertEquals("PendingJoin(eventId=${testEventId("event-1")}, phase=Loading, linkKey=present)", written)
    }

    @Test
    fun `a pending join with no key says so`() {
        assertEquals(
            "PendingJoin(eventId=${testEventId("event-1")}, phase=Loading, linkKey=null)",
            PendingJoin(testEventId("event-1"), JoinProgress.Loading).toString(),
        )
    }
}
