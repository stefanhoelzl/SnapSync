package app.snapsync.application.membership

import app.snapsync.application.EntryPointCall
import app.snapsync.data.backend.json
import app.snapsync.data.backend.ok
import app.snapsync.data.backend.text
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.membership.EVENT
import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.OTHER
import app.snapsync.data.membership.PendingLeaves
import app.snapsync.data.membership.PendingLeavesRead
import app.snapsync.data.membership.active
import app.snapsync.data.membership.enrolled
import app.snapsync.data.membership.event
import app.snapsync.data.store.Stored
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.membership.JoinChoice
import app.snapsync.domain.membership.KeptKey
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.MintsKeptKey
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import app.snapsync.format.EncryptionText
import app.snapsync.format.TimestampText
import app.snapsync.levers.WriteKind
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Joining: enrolled only within the rules, entered only once admitted; leaving: all or nothing. */
@OptIn(EntryPointCall::class, MintsKeptKey::class)
class JoinAndLeaveTest {

    private val device = Device()
    private val enrollDevice =
        EnrollDevice(
            device.membership,
            device.identity,
            app.snapsync.data.membership.EventJoin(device.gateway),
            testKeyIds,
        )

    /** A join's enrolment as its entry point runs it: admitted first, then — every rule held — sent. */
    private suspend fun enroll(choice: JoinChoice): EnrollOutcome = when (val admitted = enrollDevice.admit(choice)) {
        is Admitted -> enrollDevice(admitted, KeptKey(admitted.membership.event.id))
        is EnrollOutcome.NotEnrolled -> admitted
    }

    private val enter = EnterMembership(device.membership)
    private val leave = LeaveEvent(device.membership)

    private fun choice(
        id: String = EVENT,
        from: String = "2026-07-14T18:00:00Z",
        until: String = "2026-07-21T18:00:00Z",
        key: String? = null,
        keyId: String? = null,
    ) = JoinChoice(
        event = event(id).copy(encryption = EncryptionText.parse(keyId?.let(::testKeyId))),
        invite = InviteLink(testEventId(id), key?.let(::testKey)),
        from = CaptureCutoff(TimestampText.parse(from)),
        until = CaptureCeiling(TimestampText.parse(until)),
        direction = Direction.Both,
        saveToAlbum = false,
    )

    @Test
    fun `an admitted device is enrolled with the event and its chosen share, and nothing is written yet`() = runTest {
        device.transport.answer = { ok(200) }
        val enrolled = assertIs<EnrollOutcome.Enrolled>(enroll(choice()))
        assertEquals(active(), enrolled.evidence.membership)
        assertIs<MembershipRead.NotJoined>(device.membership.read(), "entering is its own step")
    }

    @Test
    fun `a range that breaks the rule and a key that does not open the event send nothing`() = runTest {
        for ((from, until) in listOf(
            "2026-07-22T00:00:00Z" to "2026-07-23T00:00:00Z",
            "2026-07-20T00:00:00Z" to "2026-07-15T00:00:00Z",
            "2026-06-01T00:00:00Z" to "2026-07-20T00:00:00Z",
        )) {
            assertEquals(EnrollOutcome.InvalidRange, enroll(choice(from = from, until = until)), "$from..$until")
        }
        assertEquals(EnrollOutcome.WrongLink, enroll(choice(keyId = "k1")))
        assertEquals(EnrollOutcome.WrongLink, enroll(choice(key = "k2", keyId = "k1")))
        assertEquals(emptyList(), device.transport.calls)
    }

    @Test
    fun `admitting a join asks the server nothing`() = runTest {
        assertIs<Admitted>(enrollDevice.admit(choice()))
        assertEquals(emptyList(), device.transport.calls)
    }

    @Test
    fun `a device already joined, unreadable, or nameless sends nothing`() = runTest {
        enter(enrolled())
        assertEquals(EnrollOutcome.AlreadyJoined, enroll(choice()))
        assertEquals(EnrollOutcome.JoinedElsewhere, enroll(choice(OTHER)))
        device.databases.lock()
        assertEquals(EnrollOutcome.Failed(), enroll(choice(OTHER)))
        device.databases.lock(on = false)
        leave()
        for (read in listOf(DeviceIdRead.Absent, DeviceIdRead.Unavailable("locked"))) {
            device.identity.read = read
            assertEquals(EnrollOutcome.Failed(), enroll(choice()))
        }
        assertEquals(emptyList(), device.transport.calls)
    }

    // capability `join-event`, "An unknown range shares nothing": a membership this build refuses — a range an older build
    // stored past today's rules — holds nothing a join could lose, so a join replaces it rather than failing on it.
    @Test
    fun `a membership this build refuses is replaced by a join`() = runTest {
        enter(enrolled())
        device.shared.execute {
            it.membershipQueries.updateShare(
                minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-14T18:00:00Z")),
                maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-09-01T00:00:00Z")),
                direction = Direction.Both,
                saveToAlbum = false,
                eventId = Stored.Valid(testEventId(EVENT)),
            )
        }
        assertIs<MembershipRead.Unavailable>(device.membership.read())
        device.transport.answer = { ok(200) }

        assertIs<EnrollOutcome.Enrolled>(enroll(choice(OTHER)))
    }

    @Test
    fun `the server's refusals are told apart`() = runTest {
        for ((answer, outcome) in listOf(
            json(410, """{"error":"closed"}""") to EnrollOutcome.EventOver,
            text(401, "unattested") to EnrollOutcome.Unverified,
            text(502, "down") to EnrollOutcome.Failed(ServiceFault.TROUBLE),
        )) {
            device.transport.answer = { answer }
            assertEquals(outcome, enroll(choice(key = "k1", keyId = "k1")), "$answer")
        }
    }

    @Test
    fun `an admitted membership is entered once`() {
        assertEquals(EnterOutcome.Entered(active()), enter(enrolled()))
        assertEquals(EnterOutcome.AlreadyJoined, enter(enrolled()))
        assertEquals(EnterOutcome.Failed, enter(enrolled(OTHER)), "a membership is held")
        device.databases.lock()
        assertEquals(EnterOutcome.Failed, enter(enrolled(OTHER)), "the store cannot be written")
    }

    @Test
    fun `a leave ends the membership and owes its leave`() {
        enter(enrolled())
        val left = assertIs<LeaveOutcome.Left>(leave())
        assertEquals(testEventId(EVENT), left.evidence.event)
        assertEquals(PendingLeavesRead.Ok(setOf(testEventId(EVENT))), PendingLeaves(device.shared).outstanding())
        assertEquals(LeaveOutcome.NotJoined, leave())
    }

    @Test
    fun `a leave that cannot be written, or read, changes nothing`() {
        enter(enrolled())
        device.databases.fail("ledger.db", "pendingLeave", WriteKind.INSERT)
        assertEquals(LeaveOutcome.Failed, leave())
        device.databases.lock()
        assertEquals(LeaveOutcome.Failed, leave())
        device.databases.lock(on = false)
        assertEquals(MembershipRead.Joined(Membership.Active(active().event, active().share)), device.membership.read())
    }
}
