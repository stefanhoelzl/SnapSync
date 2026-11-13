package app.snapsync.application.receiving

import app.snapsync.application.EntryPointCall
import app.snapsync.application.membership.DEVICE_ID
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.membership.OTHER
import app.snapsync.data.membership.active
import app.snapsync.data.receiving.DeliveredPhotos
import app.snapsync.data.receiving.ReceivedReporting
import app.snapsync.data.receiving.ReportedReceived
import app.snapsync.domain.receiving.HeldPosition
import app.snapsync.domain.receiving.ReceivingVerdict
import app.snapsync.domain.receiving.ReportScope
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
        ReportedReceived(),
        DeliveredPhotos(receiver.store),
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
    fun `a membership that receives nothing is owed nothing, and reads nothing to say so`() = runTest {
        r.listed += Listed("A")
        r.unionAnswer = HttpResult.Unreachable("offline")
        assertSame(
            HeldPosition.ReceivesNothing,
            holds(membership, ReceivingVerdict.SharesOnly, UnionTrigger.LEAVE_CHECK),
        )
        assertEquals(0, r.device.transport.requests.size)
    }

    @Test
    fun `any doubt holds nothing`() = runTest {
        r.unionAnswer = HttpResult.Unreachable("offline")
        assertSame(HeldPosition.Lacks, holds(membership, receives, UnionTrigger.LEAVE_CHECK), "no union")
        r.unionAnswer = null

        for (unread in listOf(DeviceIdRead.Unavailable("keychain locked"))) {
            r.device.identity.read = unread
            assertSame(HeldPosition.Lacks, holds(membership, receives, UnionTrigger.LEAVE_CHECK), "$unread")
        }
        r.device.identity.read = DeviceIdRead.Ok(app.snapsync.testids.testDeviceId(DEVICE_ID), DeviceIdRead.Via.READ)

        r.unreadable()
        assertSame(HeldPosition.Lacks, holds(membership, receives, UnionTrigger.LEAVE_CHECK), "no store")
    }

    @Test
    fun `a position is worth telling only once the event has ended`() = runTest {
        val report = reportOver()
        assertEquals(ReportScope.HELD, report.due(membership, receives), "the device's clock is past the event's end")

        r.device.clock.now = DURING_THE_EVENT
        assertEquals(ReportScope.NONE, report.due(membership, receives))
        assertEquals(
            ReportScope.HELD,
            report.due(membership, ReceivingVerdict.SharesOnly),
            "a share-only membership tells it at once",
        )
    }

    @Test
    fun `while the event runs, the photos added to the library are told at once, and only once`() = runTest {
        val report = reportOver()
        r.device.clock.now = DURING_THE_EVENT
        r.sql(
            "INSERT INTO eventRef (eventId, sourceDeviceId, sourceAssetId) " +
                "VALUES ('${membership.event.id.value}', '$SENDER', 'A')",
            "INSERT INTO downloadAsset (sourceDeviceId, sourceAssetId, state, creationDate, createdLocalId, " +
                "downloadedAt, addedAt, addedBy) VALUES ('$SENDER', 'A', 'IMPORTED', '2026-07-16T11:59:00Z', " +
                "'LOCAL-A', '2026-07-16T11:59:30Z', '2026-07-16T11:59:31Z', 'OnSilentPush')",
        )

        assertEquals(ReportScope.FACTS, report.due(membership, receives))
        report.facts(membership)

        assertEquals(1, reports())
        val body = r.device.transport.requests.last().body.orEmpty()
        assertTrue("\"position\":0" in body, "it claims no position held: $body")
        assertTrue("\"added\":\"2026-07-16T11:59:31Z\"" in body, body)
        assertEquals(ReportScope.NONE, report.due(membership, receives), "told: nothing waits any more")
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
        assertEquals(2, reports(), "lacking says position 0, which a higher one told already covers")
    }

    @Test
    fun `a share-only membership says it receives nothing once, and receiving again clears it`() = runTest {
        val report = reportOver()
        report(membership, HeldPosition.ReceivesNothing)
        report(membership, HeldPosition.ReceivesNothing)
        assertEquals(1, reports())
        assertTrue(r.device.transport.requests.last().body.orEmpty().contains("\"receivesNothing\":true"))

        report.receivesAgain(membership)
        assertEquals(2, reports(), "a switch to receiving tells the event at once")
        assertFalse(r.device.transport.requests.last().body.orEmpty().contains("receivesNothing"))

        report(membership, HeldPosition.Lacks)
        assertEquals(2, reports(), "already told: it receives")
    }

    @Test
    fun `untold delivery facts that cannot be read leave the report as it was, and say so`() = runTest {
        val report = reportOver()
        r.unreadable()

        val lines = linesOf("ReportReceivedPosition") { report(membership, HeldPosition.Holds(3)) }

        assertEquals(1, reports())
        assertFalse(r.device.transport.requests.last().body.orEmpty().contains("delivered"))
        assertTrue("the received photos' delivery facts were not read" in lines.messages, "${lines.messages}")
    }

    @Test
    fun `a position the backend did not record is told again at the next wake`() = runTest {
        val report = reportOver()
        r.receivedAnswer = HttpResult.Unreachable("offline")

        val lines = linesOf("ReportReceivedPosition") { report(membership, HeldPosition.Holds(3)) }
        assertEquals(listOf("what this device received was not recorded"), lines.messages)

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
        assertNull(ReportedReceived().last(membership.event.id))
    }

    private companion object {
        /** Before the fixture event's range ends — the fixture clock's own instant is past it. */
        val DURING_THE_EVENT = Instant.parse("2026-07-16T12:00:00Z")
    }
}
