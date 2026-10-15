package app.snapsync.data.membership

import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.StoreWrite
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventKeyId
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.MemberCounts
import app.snapsync.domain.membership.ConfirmedDeletion
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.MintsConfirmedDeletion
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import app.snapsync.format.TimestampText
import app.snapsync.levers.WriteKind
import app.snapsync.mock.DatabasesMock
import app.snapsync.testids.testEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * This device's membership in the shared database: "not joined" only from a store that answered, every write after the
 * entry a compare-and-set, and a leave and a share change each one transaction.
 */
private const val START = "2026-07-14T18:00:00Z"
private const val END = "2026-07-21T18:00:00Z"

@OptIn(MintsConfirmedDeletion::class)
class MembershipRepositoryTest {

    private val device = DatabasesMock()
    private val shared = sharedOver(device)
    private val repository = MembershipRepository(shared)

    private fun deletion(id: String = EVENT) = ConfirmedDeletion(testEventId(id))

    private fun manifestVersion(): Long = shared.use { it.ledgerQueries.selectManifestVersion().executeAsOne() }

    private fun owed(): PendingLeavesRead = PendingLeaves(shared).outstanding()

    // ---- reading ----

    @Test
    fun `a store that answered with no membership is not joined, and building the store reads nothing`() {
        assertEquals(emptyList(), device.opened, "building it opened nothing")
        assertIs<MembershipRead.NotJoined>(repository.read())
        assertIs<MembershipRead.NotJoined>(repository.state.value)
    }

    @Test
    fun `a membership still in its legacy file is unavailable, never not joined`() {
        val legacy = MembershipRepository(SharedDatabase(device.port(), legacy = null))
        assertIs<MembershipRead.Unavailable>(legacy.read())
    }

    /** The membership row as written raw, each date as [dates] give it — a row a build of the past may have left. */
    private fun stored(
        name: String = "Lake weekend",
        from: String = START,
        start: String = START,
        end: String = END,
        until: String = END,
    ) =
        shared.use {
            it.membershipQueries.saveMembership(
                eventId = testEventId(EVENT),
                name = name,
                minPhotoDate = CaptureCutoff(TimestampText.parse(from)),
                startsAt = EventStart(TimestampText.parse(start)),
                endsAt = EventEnd(TimestampText.parse(end)),
                maxPhotoDate = CaptureCeiling(TimestampText.parse(until)),
                deletesAt = DeletesAt(TimestampText.parse("2026-08-21T18:00:00Z")),
                direction = Direction.Both,
                saveToAlbum = false,
                membersActive = 3,
                membersSettled = 1,
                membersWaiting = null,
                keyId = "k1",
                openUntil = null,
                deleted = false,
            )
        }

    @Test
    fun `an entry and an update write every fact, a key and each count included`() {
        val counted = active().event.copy(keyId = EventKeyId("k1"), members = MemberCounts(3, 1, 2))
        repository.enter(enrolled(Membership.Active(counted, active().share)))
        assertEquals(counted, (repository.read() as MembershipRead.Joined).membership.event)

        for (counts in listOf(MemberCounts(4, 2), MemberCounts(5, 2, 1), null)) {
            repository.update(Membership.Active(counted, active().share), counted.copy(members = counts))
            assertEquals(counts, (repository.read() as MembershipRead.Joined).membership.event.members)
        }

        repository.leave()
        val unwaited = active().event.copy(members = MemberCounts(3, 1))
        repository.enter(enrolled(Membership.Active(unwaited, active().share)))
        assertEquals(unwaited, (repository.read() as MembershipRead.Joined).membership.event)
    }

    @Test
    fun `a stored row reads whole, its key and its members included`() {
        stored()
        val event = (repository.read() as MembershipRead.Joined).membership.event
        assertEquals(EventKeyId("k1"), event.keyId)
        assertEquals(MemberCounts(3, 1), event.members)
    }

    @Test
    fun `a row the domain refuses is unavailable, never guessed around`() {
        for (row in listOf<() -> Unit>(
            { stored(name = "  ") },
            { stored(name = "x".repeat(101)) },
            { stored(start = END, end = START) },
            { stored(from = END, until = START) },
            { stored(from = "2026-06-01T00:00:00Z") },
            { stored(from = "2026-07-22T00:00:00Z", until = "2026-07-23T00:00:00Z") },
        )) {
            row()
            assertIs<MembershipRead.Unavailable>(repository.read())
        }
    }

    @Test
    fun `a locked store is unavailable, and a reload keeps the last conclusive read`() {
        repository.enter(enrolled())
        device.lock()
        assertIs<MembershipRead.Unavailable>(repository.read())
        repository.reload()
        assertEquals(MembershipRead.Joined(active()), repository.state.value, "a passing failure keeps the membership")

        val fresh = MembershipRepository(sharedOver(device))
        fresh.reload()
        assertIs<MembershipRead.Unavailable>(fresh.state.value, "with nothing conclusive yet, unavailable it is")
    }

    @Test
    fun `a reload keeps not joined through a locked read`() {
        assertIs<MembershipRead.NotJoined>(repository.state.value)
        device.lock()
        repository.reload()
        assertIs<MembershipRead.NotJoined>(repository.state.value)
    }

    // ---- entering ----

    @Test
    fun `an entry is held, and what is held refuses another`() {
        assertEquals(EnterResult.Entered(active()), repository.enter(enrolled()))
        assertEquals(MembershipRead.Joined(active()), repository.state.value)
        assertEquals(EnterResult.AlreadyJoined, repository.enter(enrolled()))
        assertEquals(EnterResult.JoinedElsewhere, repository.enter(enrolled(OTHER)))
        assertEquals(MembershipRead.Joined(active()), repository.read(), "neither wrote anything")
    }

    @Test
    fun `an entry the store cannot write fails`() {
        device.lock()
        assertIs<EnterResult.Failed>(repository.enter(enrolled()))
    }

    // ---- the compare-and-set writes ----

    @Test
    fun `a deletion marks the active membership once`() {
        repository.enter(enrolled())
        val marked = assertIs<MarkResult.Marked>(repository.markDeleted(active(), deletion()))
        assertEquals(Membership.Deleted(active().event, active().share), marked.deleted)
        assertEquals(MembershipRead.Joined(marked.deleted), repository.state.value, "the membership stays, deleted")
        assertEquals(MarkResult.Superseded, repository.markDeleted(active(), deletion()))
    }

    @Test
    fun `an update folds the server's facts into the active membership only`() {
        repository.enter(enrolled())
        val renamed = active().event.copy(name = name("Lake week"))
        assertEquals(
            UpdateResult.Updated(Membership.Active(renamed, active().share)),
            repository.update(active(), renamed),
        )
        assertEquals(renamed, (repository.read() as MembershipRead.Joined).membership.event)

        repository.markDeleted(active(), deletion())
        assertEquals(
            UpdateResult.Superseded,
            repository.update(active(), active().event),
            "a deleted one is never updated",
        )
    }

    @Test
    fun `a reconfigure replaces the share and advances the manifest version together`() {
        repository.enter(enrolled())
        val before = manifestVersion()
        val narrowed = share(direction = Direction.UploadOnly, from = "2026-07-15T00:00:00Z")
        assertEquals(
            UpdateResult.Updated(Membership.Active(active().event, narrowed)),
            repository.reconfigure(active(), narrowed),
        )
        assertEquals(narrowed, (repository.read() as MembershipRead.Joined).membership.share)
        assertEquals(before + 1, manifestVersion())

        assertEquals(
            UpdateResult.Superseded,
            repository.reconfigure(active(OTHER), narrowed),
            "another event's changes nothing",
        )
        assertEquals(before + 1, manifestVersion(), "and advances nothing")
    }

    @Test
    fun `a reconfigure the store refuses changes nothing at all`() {
        repository.enter(enrolled())
        val before = manifestVersion()
        device.fail(LEDGER, "manifestVersion", WriteKind.UPDATE)
        assertIs<UpdateResult.Failed>(repository.reconfigure(active(), share(direction = Direction.UploadOnly)))
        assertEquals(
            active().share,
            (repository.read() as MembershipRead.Joined).membership.share,
            "the share rolled back",
        )
        assertEquals(before, manifestVersion())
    }

    // ---- leaving ----

    @Test
    fun `a leave ends the membership, owes the leave and forgets the checks, together`() {
        repository.enter(enrolled())
        val checks = EventCheckRecord(shared)
        checks.stamp(
            EventCheck.STATE,
            testEventId(EVENT),
            TimestampText.parse("2026-07-22T12:00:00Z"),
        )

        val left = assertIs<LeaveResult.Left>(repository.leave())
        assertEquals(testEventId(EVENT), left.evidence.event)
        assertIs<MembershipRead.NotJoined>(repository.state.value)
        assertEquals(PendingLeavesRead.Ok(setOf(testEventId(EVENT))), owed())
        assertEquals(app.snapsync.domain.membership.CheckedAt.Never, checks.last(EventCheck.STATE, testEventId(EVENT)))
    }

    @Test
    fun `a leave the store refuses leaves the member fully joined and owes nothing`() {
        repository.enter(enrolled())
        device.fail(LEDGER, "pendingLeave", WriteKind.INSERT)
        assertIs<LeaveResult.Failed>(repository.leave())
        assertEquals(MembershipRead.Joined(active()), repository.read(), "the membership rolled back")
        assertEquals(PendingLeavesRead.Ok(emptySet()), owed())
    }

    @Test
    fun `a leave with no membership held changes nothing`() {
        assertEquals(LeaveResult.NotJoined, repository.leave())
        assertEquals(PendingLeavesRead.Ok(emptySet()), owed())
    }

    @Test
    fun `forgetting ends the membership and its checks, and owes no leave`() {
        repository.enter(enrolled())
        val checks = EventCheckRecord(shared)
        checks.stamp(EventCheck.PHOTOS, testEventId(EVENT), TimestampText.parse("2026-07-22T12:00:00Z"))

        assertEquals(StoreWrite.Written, repository.forget())
        assertIs<MembershipRead.NotJoined>(repository.state.value)
        assertEquals(PendingLeavesRead.Ok(emptySet()), owed())
        assertEquals(app.snapsync.domain.membership.CheckedAt.Never, checks.last(EventCheck.PHOTOS, testEventId(EVENT)))
        device.lock()
        assertIs<StoreWrite.Failed>(repository.forget())
    }

    @Test
    fun `a write to a store that cannot be opened fails`() {
        repository.enter(enrolled())
        device.lock()
        assertIs<MarkResult.Failed>(repository.markDeleted(active(), deletion()))
        assertIs<UpdateResult.Failed>(repository.update(active(), active().event))
        assertIs<UpdateResult.Failed>(repository.reconfigure(active(), active().share))
        assertIs<LeaveResult.Failed>(repository.leave())
    }
}
