package app.snapsync.application.receiving

import app.snapsync.application.EntryPointCall
import app.snapsync.data.membership.active
import app.snapsync.data.membership.share
import app.snapsync.domain.event.KeyFact
import app.snapsync.domain.membership.ConfirmedNotJoined
import app.snapsync.domain.membership.LeftMembership
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.MintsConfirmedNotJoined
import app.snapsync.domain.membership.MintsLeftMembership
import app.snapsync.domain.receiving.ReceivingVerdict
import app.snapsync.domain.receiving.UnionTrigger
import app.snapsync.domain.sharing.Direction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Stopping receiving — every transfer cancelled, every photo not yet received dropped with its bytes, the received
 * ones kept as handles — for each reason it may stop; and whether a membership receives at all.
 */
@OptIn(EntryPointCall::class, MintsLeftMembership::class, MintsConfirmedNotJoined::class)
class StopReceivingTest {

    private val r = Receiver()
    private val stop = StopReceiving(r.cleanup)

    @Test
    fun `a stop cancels every transfer and frees what was not received`() = runTest {
        val membership = r.joined()
        r.listed += Listed("A")
        r.position = 1
        PlanReceiving(r.plans, r.union, r.device.identity, r.checks, r.device.wallClock)(
            receiving(membership),
            UnionTrigger.FOREGROUND,
        )
        val reasons = listOf(
            StopReason.Left(LeftMembership(membership.event.id)),
            StopReason.Deleted(Membership.Deleted(membership.event, membership.share)),
            StopReason.NotJoined(ConfirmedNotJoined()),
            StopReason.SharesOnly(membership),
        )

        for (reason in reasons) {
            assertEquals(listOf("receiving stopped"), linesOf("StopReceiving") { stop(reason) }.messages, "$reason")
        }
        assertEquals(reasons.size, r.download.cancelled)
    }

    @Test
    fun `a store that cannot be read is told, and stops nothing`() = runTest {
        r.unreadable()
        assertEquals(
            listOf("receiving could not be stopped"),
            linesOf("StopReceiving") { stop(StopReason.NotJoined(ConfirmedNotJoined())) }.messages,
        )
    }

    @Test
    fun `a membership receives by its direction and its event's key`() {
        val receivingOf = ReceivingOf(r.keys)
        val both = active()
        assertEquals(KeyFact.Absent, r.keys.state.value)

        assertSame(both, assertIs<ReceivingVerdict.Receives>(receivingOf(both)).receiving.membership)
        val sharesOnly = Membership.Active(both.event, share(both.event, direction = Direction.UploadOnly))
        assertSame(ReceivingVerdict.SharesOnly, receivingOf(sharesOnly))
    }
}
