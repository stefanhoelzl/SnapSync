package app.snapsync.application.membership

import app.snapsync.application.EntryPointCall
import app.snapsync.data.backend.json
import app.snapsync.data.backend.ok
import app.snapsync.data.backend.text
import app.snapsync.data.event.EventDirectory
import app.snapsync.data.event.EventRename
import app.snapsync.data.membership.EVENT
import app.snapsync.data.membership.EventCheckRecord
import app.snapsync.data.membership.LeaveNotifier
import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.OTHER
import app.snapsync.data.membership.PendingLeaves
import app.snapsync.data.membership.PendingLeavesRead
import app.snapsync.data.membership.active
import app.snapsync.data.membership.enrolled
import app.snapsync.data.membership.name
import app.snapsync.data.store.StoreWrite
import app.snapsync.domain.membership.CheckCadence
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.ShareChoice
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import app.snapsync.format.TimestampText
import app.snapsync.levers.WriteKind
import app.snapsync.ports.HttpResult
import app.snapsync.testids.testEventId
import app.snapsync.wire.models.EventDetails
import app.snapsync.wire.runtime.WireJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant
import app.snapsync.wire.models.Event as WireEvent
import app.snapsync.wire.models.MemberCounts as WireCounts

/** A joined membership's changes: the server's word, the member's settings, a rename, and the leaves owed. */
@OptIn(EntryPointCall::class)
class MembershipChangesTest {

    private val device = Device().apply { EnterMembership(membership)(enrolled()) }
    private val refresh = RefreshMembership(device.membership, EventDirectory(device.gateway), device.wallClock)

    private fun found(name: String = "Lake weekend", completedAt: String? = null) = json(
        200,
        WireJson.encodeToString(
            EventDetails.serializer(),
            EventDetails(
                eventId = EVENT, name = name, createdAt = "2026-07-01T10:00:00Z", startsAt = "2026-07-14T18:00:00Z",
                endsAt = "2026-07-21T18:00:00Z", openUntil = "2026-07-21T18:00:00Z", deletesAt = "2026-08-21T18:00:00Z",
                closedAt = null, completedAt = completedAt, members = WireCounts(3, 1, 2),
            ),
        ),
    )

    private fun answering(result: HttpResult) {
        device.transport.answer = { result }
    }

    private fun held(): Membership = (device.membership.read() as MembershipRead.Joined).membership

    // ---- the server's word ----

    @Test
    fun `the server's word that the photos are deleted marks the membership, which stays`() = runTest {
        answering(found(completedAt = "2026-08-01T00:00:00Z"))
        val deleted = assertIs<RefreshOutcome.Deleted>(refresh(active()))
        assertEquals(deleted.deleted, held())
        assertEquals(RefreshOutcome.Superseded, refresh(active()), "deleted once")
    }

    @Test
    fun `not found deletes only past the membership's own deadline`() = runTest {
        answering(ok(404))
        assertEquals(RefreshOutcome.Inconclusive, refresh(active()))
        device.clock.now = Instant.parse("2026-08-21T18:00:01Z")
        assertIs<RefreshOutcome.Deleted>(refresh(active()))
    }

    @Test
    fun `the name, members and open-until date follow the server, and no answer changes nothing`() = runTest {
        answering(found(name = "Lake week"))
        val current = assertIs<RefreshOutcome.Current>(refresh(active()))
        assertEquals("Lake week", current.active.event.name.text)
        assertEquals(current.active, held())
        assertEquals(RefreshOutcome.Current(current.active), refresh(current.active), "unchanged writes nothing")

        answering(text(502, "down"))
        assertEquals(RefreshOutcome.Inconclusive, refresh(current.active))
    }

    @Test
    fun `a refresh of a membership no longer held writes nothing, and one that cannot write changes nothing`() = runTest {
        answering(found(name = "Lake week"))
        assertEquals(
            RefreshOutcome.Superseded,
            refresh(
                active(OTHER).let {
                    Membership.Active(it.event.copy(id = testEventId(OTHER)), it.share)
                },
            ),
        )
        device.databases.fail("ledger.db", "membership", WriteKind.UPDATE)
        assertEquals(RefreshOutcome.Current(active()), refresh(active()))
        answering(found(completedAt = "2026-08-01T00:00:00Z"))
        device.databases.fail("ledger.db", "membership", WriteKind.UPDATE)
        assertEquals(RefreshOutcome.Inconclusive, refresh(active()))
    }

    // ---- the member's settings ----

    private val reconfigure = ReconfigureEvent(device.membership)

    private fun settings(id: String = EVENT, from: String = "2026-07-15T00:00:00Z", direction: Direction = Direction.UploadOnly) =
        ShareChoice(
            testEventId(id),
            CaptureCutoff(TimestampText.parse(from)),
            CaptureCeiling(TimestampText.parse("2026-07-21T18:00:00Z")),
            direction,
            saveToAlbum = true,
        )

    @Test
    fun `settings are saved whole`() {
        val saved = assertIs<ReconfigureOutcome.Saved>(reconfigure(active(), settings()))
        assertEquals(saved.active, held())
        assertEquals(Direction.UploadOnly, saved.active.share.direction)
    }

    @Test
    fun `settings that break the rule, are for another membership, or cannot be saved change nothing`() {
        for (from in listOf("2026-08-01T00:00:00Z", "2026-06-01T00:00:00Z")) {
            assertEquals(ReconfigureOutcome.Refused, reconfigure(active(), settings(from = from)), from)
        }
        val outside = settings(from = "2026-07-22T00:00:00Z")
            .copy(until = CaptureCeiling(TimestampText.parse("2026-07-23T00:00:00Z")))
        assertEquals(ReconfigureOutcome.Refused, reconfigure(active(), outside))
        assertEquals(ReconfigureOutcome.NotCurrent, reconfigure(active(), settings(id = OTHER)))
        device.databases.fail("ledger.db", "membership", WriteKind.UPDATE)
        assertEquals(ReconfigureOutcome.SaveFailed, reconfigure(active(), settings()))
        LeaveEvent(device.membership)()
        assertEquals(ReconfigureOutcome.NotCurrent, reconfigure(active(), settings()))
    }

    // ---- the rename ----

    private val rename = RenameEvent(device.membership, EventRename(device.gateway))

    private fun changed(name: String) = json(
        200,
        WireJson.encodeToString(
            WireEvent.serializer(),
            WireEvent(
                eventId = EVENT, name = name, createdAt = "2026-07-01T10:00:00Z", startsAt = "2026-07-14T18:00:00Z",
                endsAt = "2026-07-21T18:00:00Z", openUntil = "2026-07-21T18:00:00Z", deletesAt = "2026-08-21T18:00:00Z",
                closedAt = null, completedAt = null,
            ),
        ),
    )

    @Test
    fun `a rename keeps the server's name`() = runTest {
        answering(changed("Lake week"))
        assertEquals(RenameOutcome.Renamed(name("Lake week")), rename(active(), testEventId(EVENT), " Lake week "))
        assertEquals("Lake week", held().event.name.text)
    }

    @Test
    fun `a name that is none is refused before anything is sent, and the server's no is told apart`() = runTest {
        assertEquals(RenameOutcome.InvalidName, rename(active(), testEventId(EVENT), "  "))
        assertEquals(RenameOutcome.InvalidName, rename(active(), testEventId(EVENT), "x".repeat(101)))
        assertEquals(emptyList(), device.transport.calls)
        answering(text(400, "no"))
        assertEquals(RenameOutcome.InvalidName, rename(active(), testEventId(EVENT), "x"))
        answering(text(502, "down"))
        assertEquals(RenameOutcome.Failed(ServiceFault.TROUBLE), rename(active(), testEventId(EVENT), "x"))
    }

    @Test
    fun `the server's word that the photos are deleted ends the rename and marks the membership, which stays`() =
        runTest {
            answering(json(410, """{"error":"closed"}"""))
            assertEquals(RenameOutcome.EventOver, rename(active(), testEventId(EVENT), "x"))
            assertIs<Membership.Deleted>(held(), "the app never leaves an event on its own")
            assertEquals("Lake weekend", held().event.name.text, "nothing was renamed")
        }

    @Test
    fun `a lone not found is no word of a deletion`() = runTest {
        answering(text(404, "event not found"))
        assertEquals(RenameOutcome.Failed(fault = null), rename(active(), testEventId(EVENT), "x"))
        assertIs<Membership.Active>(held())
    }

    // ---- forgetting ----

    @Test
    fun `forgetting ends the membership and tells no backend`() {
        assertEquals(StoreWrite.Written, ForgetMembership(device.membership)())
        assertIs<MembershipRead.NotJoined>(device.membership.read())
        assertEquals(PendingLeavesRead.Ok(emptySet()), PendingLeaves(device.shared).outstanding())
        assertEquals(emptyList(), device.transport.calls)
    }

    // ---- the leaves owed, and the hourly read ----

    @Test
    fun `owed leaves are delivered, and one the server does not confirm waits`() = runTest {
        LeaveEvent(device.membership)()
        val deliver = DeliverPendingLeaves(PendingLeaves(device.shared), LeaveNotifier(device.gateway, device.identity))
        answering(text(502, "down"))
        assertEquals(DeliveryOutcome.Delivered(confirmed = 0, outstanding = 1), deliver())
        answering(ok(200))
        assertEquals(DeliveryOutcome.Delivered(confirmed = 1, outstanding = 0), deliver())
        assertEquals(PendingLeavesRead.Ok(emptySet()), PendingLeaves(device.shared).outstanding())
        device.databases.lock()
        assertEquals(DeliveryOutcome.Unreadable, deliver())
    }

    @Test
    fun `a bounded wake reads an ended event's state at most once an hour, any other whenever it wakes`() {
        val claim = ClaimStateCheck(EventCheckRecord(device.shared), device.wallClock)
        assertEquals(CheckCadence.Due, claim(active(), bounded = true))
        assertEquals(CheckCadence.NotYet, claim(active(), bounded = true))
        assertEquals(CheckCadence.Due, claim(active(), bounded = false))
        device.clock.now = Instant.parse("2026-07-22T13:00:00Z")
        assertEquals(CheckCadence.Due, claim(active(), bounded = true))
    }

    @Test
    fun `an event not yet ended is never read for its state`() {
        val claim = ClaimStateCheck(EventCheckRecord(device.shared), device.wallClock)
        for (now in listOf("2026-07-01T12:00:00Z", "2026-07-20T12:00:00Z")) {
            device.clock.now = Instant.parse(now)
            assertEquals(CheckCadence.NotYet, claim(active(), bounded = false), "upcoming or running: $now")
        }
    }

    @Test
    fun `a rename of an event no longer joined changes nothing`() = runTest {
        answering(changed("Lake week"))
        assertEquals(RenameOutcome.NotCurrent, rename(active(), testEventId("other"), "Lake week"))
        assertEquals(emptyList(), device.transport.calls)
    }
}
