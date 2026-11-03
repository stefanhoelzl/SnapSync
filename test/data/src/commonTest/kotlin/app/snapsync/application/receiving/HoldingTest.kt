package app.snapsync.application.receiving

import app.snapsync.application.EntryPointCall
import app.snapsync.application.membership.DEVICE_ID
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.membership.OTHER
import app.snapsync.data.membership.active
import app.snapsync.data.receiving.ReceivedReporting
import app.snapsync.data.receiving.ReportedPositions
import app.snapsync.domain.receiving.HeldPosition
import app.snapsync.domain.receiving.ReceivingVerdict
import app.snapsync.domain.receiving.UnionTrigger
import app.snapsync.ports.HttpResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * How far this device holds the others' photos (decision record `changes/back-dated-events`, D4 and D7): the position
 * a whole read covers, only when every foreign photo it served is settled here — and, once the event has ended, that
 * position told to the event, only when higher than the last one told, and only for the membership still joined.
 */
@OptIn(EntryPointCall::class)
class HoldingTest {

    private val r = Receiver()
    private val holds = HoldsEverything(r.counts, r.union, r.device.identity)
    private val membership = r.joined()
    private val receives = ReceivingVerdict.Receives(receiving(membership))

    private fun reportOver(receiver: Receiver = r) = ReportReceivedPosition(
        ReceivedReporting(receiver.device.gateway, receiver.device.identity),
        ReportedPositions(),
        receiver.device.wallClock,
        receiver.device.membership,
    )

    private fun reports(receiver: Receiver = r) = receiver.device.transport.requests.count {
        it.url.contains(
            "/received",
        )
    }

    @Test
    fun `a membership holds the position once every foreign photo is settled here`() = runTest {
        r.listed += Listed("OWN", device = DEVICE_ID)
        r.position = 3
        assertEquals(3L, assertIs<HeldPosition.Holds>(holds(membership, receives, UnionTrigger.LEAVE_CHECK)).position)

        r.listed += Listed("A")
        assertSame(HeldPosition.Lacks, holds(membership, receives, UnionTrigger.LEAVE_CHECK), "A has not arrived")
        assertSame(HeldPosition.Lacks, holds(membership, ReceivingVerdict.KeyLost, UnionTrigger.LEAVE_CHECK))
    }

    @Test
    fun `a membership that receives nothing holds whatever the union lists`() = runTest {
        r.listed += Listed("A")
        r.position = 1
        assertIs<HeldPosition.Holds>(holds(membership, ReceivingVerdict.SharesOnly, UnionTrigger.LEAVE_CHECK))
    }

    @Test
    fun `any doubt holds nothing`() = runTest {
        r.unionAnswer = HttpResult.Unreachable("offline")
        assertSame(HeldPosition.Lacks, holds(membership, receives, UnionTrigger.LEAVE_CHECK), "no union")
        r.unionAnswer = null

        for (unread in listOf(DeviceIdRead.Absent, DeviceIdRead.Unavailable("keychain locked"))) {
            r.device.identity.read = unread
            assertSame(HeldPosition.Lacks, holds(membership, receives, UnionTrigger.LEAVE_CHECK), "$unread")
        }
        r.device.identity.read = DeviceIdRead.Ok(app.snapsync.testids.testDeviceId(DEVICE_ID), DeviceIdRead.Via.READ)

        r.unreadable()
        assertSame(HeldPosition.Lacks, holds(membership, receives, UnionTrigger.LEAVE_CHECK), "no store")
    }

    @Test
    fun `a position is worth telling only once the event has ended`() {
        val report = reportOver()
        assertTrue(report.due(membership), "the device's clock is past the event's end")

        r.device.clock.now = Instant.parse("2026-07-16T12:00:00Z")
        assertFalse(report.due(membership))
    }

    @Test
    fun `a held position is told once, and a higher one again`() = runTest {
        val report = reportOver()

        report(membership, HeldPosition.Holds(3))
        report(membership, HeldPosition.Holds(3))
        assertEquals(1, reports(), "the backend keeps the highest: an unchanged one is not told again")

        report(membership, HeldPosition.Holds(4))
        assertEquals(2, reports())

        report(membership, HeldPosition.Lacks)
        assertEquals(2, reports(), "a doubt tells nothing")
    }

    @Test
    fun `a position the backend did not record is told again at the next wake`() = runTest {
        val report = reportOver()
        r.receivedAnswer = HttpResult.Unreachable("offline")

        val lines = linesOf("ReportReceivedPosition") { report(membership, HeldPosition.Holds(3)) }
        assertEquals(listOf("the received position was not recorded"), lines.messages)

        r.receivedAnswer = app.snapsync.data.backend.ok()
        report(membership, HeldPosition.Holds(3))
        assertEquals(2, reports())
    }

    @Test
    fun `a report is only ever about the membership the step found`() = runTest {
        reportOver()(active(OTHER), HeldPosition.Holds(3))
        assertEquals(0, reports(), "another event is joined now")

        val unjoined = Receiver()
        reportOver(unjoined)(membership, HeldPosition.Holds(3))
        assertEquals(0, reports(unjoined), "nothing is joined")

        val unreadable = Receiver().apply { device.databases.lock() }
        reportOver(unreadable)(membership, HeldPosition.Holds(3))
        assertEquals(0, reports(unreadable), "the membership cannot be read")
        assertNull(ReportedPositions().last(membership.event.id))
    }
}
