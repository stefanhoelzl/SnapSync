package app.snapsync.model

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.testids.testEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The facts the model derives: how a refusal the service sent is read, how many members an ended event waits for, and
 * whether a membership is encrypted.
 */
class ModelFactsTest {

    @Test
    fun `an ended event waits for its active members that have not settled never fewer than none`() {
        assertEquals(2, MemberCounts(active = 3, settled = 1).waitingFor)
        assertEquals(0, MemberCounts(active = 1, settled = 2).waitingFor)
    }

    @Test
    fun `a membership is encrypted by its key`() {
        val membership = EventConfig(
            eventId = testEventId("e"),
            name = "Picnic",
            minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-06T00:00:00Z")),
            endsAt = EventEnd(TimestampText.parse("2026-07-07T00:00:00Z")),
            maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-07T00:00:00Z")),
            deletesAt = DeletesAt(TimestampText.parse("2026-08-05T00:00:00Z")),
        )

        assertFalse(membership.encrypted)
        assertTrue(membership.copy(keyId = "k1").encrypted)
    }
}
