package app.snapsync.query.membership

import app.snapsync.application.EntryPointCall
import app.snapsync.application.membership.Device
import app.snapsync.application.membership.EnterMembership
import app.snapsync.application.membership.KeyIsItsId
import app.snapsync.application.membership.LeaveEvent
import app.snapsync.application.membership.RefreshMembership
import app.snapsync.data.backend.json
import app.snapsync.data.backend.ok
import app.snapsync.data.backend.text
import app.snapsync.data.event.EventDirectory
import app.snapsync.data.membership.EVENT
import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.active
import app.snapsync.data.membership.enrolled
import app.snapsync.domain.event.MemberCounts
import app.snapsync.domain.invite.InviteKey
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.membership.JoinCommit
import app.snapsync.domain.membership.RenameResult
import app.snapsync.domain.membership.SettingsResult
import app.snapsync.domain.membership.SwitchFacts
import app.snapsync.domain.request.RequestId
import app.snapsync.domain.service.ServiceFault
import app.snapsync.ports.HttpResult
import app.snapsync.query.RequestStatus
import app.snapsync.testids.testEventId
import app.snapsync.wire.models.EventDetails
import app.snapsync.wire.runtime.WireJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant
import app.snapsync.wire.models.MemberCounts as WireCounts

/** The join screen's read of an invite, and what the server says of the joined membership when another invite opens. */
@OptIn(EntryPointCall::class)
class MembershipLookupsTest {

    private val device = Device()
    private val directory = EventDirectory(device.gateway)
    private val details = JoinDetailsLookup(directory, KeyIsItsId)
    private val switch = SwitchLookup(device.membership, directory, device.wallClock)

    private fun found(keyId: String? = null, completedAt: String? = null, waiting: Int = 2) = json(
        200,
        WireJson.encodeToString(
            EventDetails.serializer(),
            EventDetails(
                eventId = EVENT, name = "Lake weekend", createdAt = "2026-07-01T10:00:00Z",
                startsAt = "2026-07-14T18:00:00Z", endsAt = "2026-07-21T18:00:00Z", openUntil = "2026-07-21T18:00:00Z",
                deletesAt = "2026-08-21T18:00:00Z", closedAt = null, completedAt = completedAt, keyId = keyId,
                members = WireCounts(active = 3, `final` = 1, waiting = waiting),
            ),
        ),
    )

    private fun answering(result: HttpResult) {
        device.transport.answer = { result }
    }

    private fun invite(key: String? = null) = InviteLink(testEventId(EVENT), key?.let(::InviteKey))

    // ---- the join screen's read ----

    @Test
    fun `an event the invite's key opens is found, with whether its photos are deleted`() = runTest {
        answering(found(keyId = "k1"))
        val read = assertIs<JoinDetails.Found>(details.load(invite("k1")))
        assertEquals(testEventId(EVENT), read.event.id)
        assertEquals(MemberCounts(3, 1, 2), read.event.members)
        assertEquals(false, read.completed)

        answering(found(completedAt = "2026-08-01T00:00:00Z"))
        assertEquals(true, assertIs<JoinDetails.Found>(details.load(invite())).completed)
    }

    @Test
    fun `an invite whose key does not open the event is the wrong link`() = runTest {
        answering(found(keyId = "k1"))
        assertEquals(JoinDetails.WrongLink, details.load(invite("other")))
        assertEquals(JoinDetails.WrongLink, details.load(invite()), "a key cut short in sharing")
    }

    @Test
    fun `not found is told apart from no answer`() = runTest {
        answering(text(404, "event not found"))
        assertEquals(JoinDetails.NotFound, details.load(invite()))
        answering(text(502, "down"))
        assertEquals(JoinDetails.Unanswered(ServiceFault.TROUBLE), details.load(invite()), "and how the service failed")
    }

    // ---- the switch's read of the joined membership ----

    @Test
    fun `a running event is never read, and every doubt is undecided`() = runTest {
        assertEquals(SwitchFacts.Undecided, switch.facts(), "not joined")
        EnterMembership(device.membership)(enrolled())
        device.clock.now = Instant.parse("2026-07-20T12:00:00Z")
        answering(found(waiting = 0))
        assertEquals(SwitchFacts.Undecided, switch.facts(), "still running")
        assertEquals(emptyList(), device.transport.calls)
    }

    @Test
    fun `an ended event the server says everyone shared, or deleted, is told as such`() = runTest {
        EnterMembership(device.membership)(enrolled())
        answering(found(waiting = 0))
        assertEquals(SwitchFacts.EveryoneShared, switch.facts())
        answering(found(completedAt = "2026-08-01T00:00:00Z"))
        assertEquals(SwitchFacts.Deleted, switch.facts())
    }

    @Test
    fun `an ended event still waiting for a member, gone, or unanswered is undecided`() = runTest {
        EnterMembership(device.membership)(enrolled())
        for (answer in listOf(found(waiting = 1), ok(404), text(502, "down"))) {
            answering(answer)
            assertEquals(SwitchFacts.Undecided, switch.facts(), "$answer")
        }
    }

    @Test
    fun `a membership marked deleted reads nothing`() = runTest {
        EnterMembership(device.membership)(enrolled())
        answering(found(completedAt = "2026-08-01T00:00:00Z"))
        RefreshMembership(device.membership, directory, device.wallClock)(active())
        val calls = device.transport.calls.size
        assertEquals(SwitchFacts.Deleted, switch.facts())
        assertEquals(calls, device.transport.calls.size)
        LeaveEvent(device.membership)()
        assertEquals(SwitchFacts.Undecided, switch.facts(), "left")
    }

    @Test
    fun `an upcoming event, and a membership that cannot be read, decide nothing`() = runTest {
        EnterMembership(device.membership)(enrolled())
        device.clock.now = Instant.parse("2026-07-01T12:00:00Z")
        assertEquals(SwitchFacts.Undecided, switch.facts(), "upcoming")
        device.databases.lock()
        assertEquals(SwitchFacts.Undecided, switch.facts(), "unreadable")
        assertEquals(null, MembershipQuery(MembershipRepository(device.shared)).active(), "unreadable: no active one")
        assertEquals(emptyList(), device.transport.calls)
    }

    @Test
    fun `the query answers the membership as this process holds it, and the active one`() = runTest {
        val query = MembershipQuery(device.membership)
        assertEquals(null, query.active(), "none")
        EnterMembership(device.membership)(enrolled())
        assertEquals(MembershipRead.Joined(active()), query.state.value)
        assertEquals(active(), query.active())
        answering(found(completedAt = "2026-08-01T00:00:00Z"))
        RefreshMembership(device.membership, directory, device.wallClock)(active())
        assertEquals(null, query.active(), "a deleted one is no active one")
    }

    @Test
    fun `each kind of request has its own status`() {
        val statuses = MembershipStatuses()
        statuses.join.done(RequestId(1), JoinCommit.EventOver)
        statuses.settings.begun(RequestId(2))
        statuses.rename.done(RequestId(3), RenameResult.InvalidName)
        assertEquals(RequestStatus.Done(RequestId(1), JoinCommit.EventOver), statuses.join.status.value)
        assertEquals(RequestStatus.InFlight(RequestId(2)), statuses.settings.status.value)
        assertEquals(RequestStatus.Idle, statuses.switch.status.value)
        assertEquals(RequestStatus.Done(RequestId(3), RenameResult.InvalidName), statuses.rename.status.value)
        assertEquals(4, SettingsResult.entries.size, "saved, not saved, not current, refused")
    }
}
