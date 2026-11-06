package app.snapsync.domain.membership

import app.snapsync.domain.album.AlbumChoice
import app.snapsync.domain.event.anEvent
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.domain.sharing.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

/** The one read of this device's membership: held, not held on the store's word, or not readable now. */
@OptIn(MintsConfirmedNotJoined::class)
class MembershipReadTest {
    private val event = anEvent()
    private val membership = Membership.Active(
        event,
        Share(
            assertIs<CaptureRangeVerdict.Ok>(
                CaptureRange.of(
                    CaptureCutoff(event.range.start.at),
                    CaptureCeiling(event.range.end.at),
                    event.range.start,
                    event.range.end,
                ),
            ).range,
            Direction.Both,
            album = AlbumChoice.LibraryOnly,
        ),
    )

    @Test
    fun `a read carries what it found`() {
        assertSame(membership, MembershipRead.Joined(membership).membership)
        val evidence = ConfirmedNotJoined()
        assertSame(evidence, MembershipRead.NotJoined(evidence).evidence)
    }

    @Test
    fun `an unreadable membership says why, and whether the store refused its row`() {
        val locked = MembershipRead.Unavailable("locked")
        assertEquals("locked", locked.detail)
        assertFalse(locked.refused, "a store that cannot be read is no refused row")
        assertEquals(true, MembershipRead.Unavailable("inverted", refused = true).refused)
    }
}
