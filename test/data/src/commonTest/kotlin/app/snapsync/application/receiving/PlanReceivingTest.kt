package app.snapsync.application.receiving

import app.snapsync.application.EntryPointCall
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.membership.EventCheck
import app.snapsync.data.receiving.UnionRead
import app.snapsync.domain.membership.CheckCadence
import app.snapsync.domain.membership.CheckedAt
import app.snapsync.domain.receiving.UnionReadPlan
import app.snapsync.domain.receiving.UnionTrigger
import app.snapsync.levers.WriteKind
import app.snapsync.ports.HttpResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.minutes

/**
 * Planning what the others' union serves (decision record `changes/incremental-union`): a whole read or a delta, nothing
 * for a push whose position is read, the open foreign photos recorded and their transfers started — and the hour
 * stamped whatever the read answered.
 */
@OptIn(EntryPointCall::class)
class PlanReceivingTest {

    private val r = Receiver()
    private val plan = PlanReceiving(r.plans, r.union, r.device.identity, r.checks, r.device.wallClock)
    private val due = PlanIfDue(r.checks, r.device.wallClock)
    private val membership = r.joined()

    private fun unionReads() = r.device.transport.requests.count { it.url.contains("/files") }

    @Test
    fun `a whole read records the open foreign photos and starts their transfers`() = runTest {
        r.listed += listOf(Listed("A"), Listed("B"))
        r.position = 2

        val planned = assertIs<PlanOutcome.Planned>(plan(receiving(membership), UnionTrigger.FOREGROUND))

        assertEquals(2, planned.planned.planned)
        assertEquals(2, planned.planned.pending)
        assertEquals(0, planned.planned.pruned)
        assertEquals(2L, planned.position)
        assertEquals(2, r.download.started.size, "one transfer per resource")
        assertIs<CheckedAt.At>(r.checks.last(EventCheck.PHOTOS, membership.event.id), "the hour is stamped")
    }

    @Test
    fun `a push whose position is already read reads nothing`() = runTest {
        r.listed += Listed("A")
        r.position = 1
        plan(receiving(membership), UnionTrigger.FOREGROUND)

        assertSame(PlanOutcome.NothingNew, plan(receiving(membership), UnionTrigger.PUSH, announced = 1))
        assertEquals(1, unionReads())

        r.listed += Listed("B")
        r.position = 2
        val delta = assertIs<PlanOutcome.Planned>(plan(receiving(membership), UnionTrigger.PUSH, announced = 2))
        assertEquals(2L, delta.position, "the position the delta covered")
        assertEquals(2, unionReads())
        assertEquals(
            "cursor=1",
            r.device.transport.requests.last().url.substringAfter('?').split('&').first { it.startsWith("cursor") },
        )
    }

    @Test
    fun `a union the caller read in this act is planned without a second read`() = runTest {
        r.listed += Listed("A")
        r.position = 1
        val page = assertIs<UnionRead.Ok>(r.union.read(membership.event.id, UnionReadPlan.Whole, UnionTrigger.JOIN))

        val planned = plan(receiving(membership), UnionTrigger.JOIN, known = page.page)

        assertEquals(1, assertIs<PlanOutcome.Planned>(planned).planned.planned)
        assertEquals(1, unionReads(), "the caller's read only")
    }

    @Test
    fun `a union that cannot be read keeps the last state, and still counts toward the hour`() = runTest {
        r.unionAnswer = HttpResult.Unreachable("offline")

        assertIs<PlanOutcome.UnionUnavailable>(plan(receiving(membership), UnionTrigger.WAKE))
        assertEquals(CheckCadence.NotYet, due(receiving(membership)))
    }

    @Test
    fun `no device id plans nothing`() = runTest {
        r.listed += Listed("A")
        for (unread in listOf(DeviceIdRead.Absent, DeviceIdRead.Unavailable("keychain locked"))) {
            r.device.identity.read = unread
            assertIs<PlanOutcome.Unavailable>(plan(receiving(membership), UnionTrigger.FOREGROUND), "$unread")
        }
        assertEquals(emptyList(), r.download.started)
    }

    @Test
    fun `a store that cannot be read or written plans nothing`() = runTest {
        r.listed += Listed("A")
        r.position = 1
        r.fail("downloadAsset", WriteKind.INSERT)
        assertIs<PlanOutcome.Unavailable>(plan(receiving(membership), UnionTrigger.FOREGROUND))

        r.unreadable()
        assertIs<PlanOutcome.Unavailable>(plan(receiving(membership), UnionTrigger.FOREGROUND))
        assertEquals(emptyList(), r.download.started)
    }

    @Test
    fun `a background wake plans once an hour`() = runTest {
        assertEquals(CheckCadence.Due, due(receiving(membership)))
        plan(receiving(membership), UnionTrigger.WAKE)
        assertEquals(CheckCadence.NotYet, due(receiving(membership)))

        r.device.clock.now += 61.minutes
        assertEquals(CheckCadence.Due, due(receiving(membership)))
    }
}
