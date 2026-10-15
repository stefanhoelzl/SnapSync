package app.snapsync.domain.membership

import app.snapsync.domain.event.EventKeyId
import app.snapsync.domain.event.anEvent
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.domain.sharing.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/** The membership's values: a deletion is the type, and the evidence names what it proves. */
@OptIn(MintsEnrolled::class, MintsLeftMembership::class, MintsConfirmedNotJoined::class)
class MembershipTest {

    private val event = anEvent()
    private val share = Share(
        assertIs<CaptureRangeVerdict.Ok>(
            CaptureRange.of(
                CaptureCutoff(event.range.start.at),
                CaptureCeiling(event.range.end.at),
                event.range.start,
                event.range.end,
            ),
        ).range,
        Direction.Both,
        saveToAlbum = false,
    )

    @Test
    fun `a deleted membership keeps its event and share, and is not the active one`() {
        val active = Membership.Active(event.copy(keyId = EventKeyId("k1")), share)
        val deleted = Membership.Deleted(active.event, active.share)
        assertEquals(active.event to active.share, deleted.event to deleted.share)
        assertNotEquals<Membership>(active, deleted)
    }

    @Test
    fun `the evidence names what it proves`() {
        val active = Membership.Active(event, share)
        assertEquals(active, Enrolled(active).membership)
        assertEquals(event.id, LeftMembership(event.id).event)
        assertIs<ConfirmedNotJoined>(ConfirmedNotJoined())
    }
}
