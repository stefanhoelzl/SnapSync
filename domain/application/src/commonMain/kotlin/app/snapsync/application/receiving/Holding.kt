package app.snapsync.application.receiving

import app.snapsync.application.EntryPointCall
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.receiving.DownloadRead
import app.snapsync.data.receiving.EventUnion
import app.snapsync.data.receiving.HeldFacts
import app.snapsync.data.receiving.ReceivedReply
import app.snapsync.data.receiving.ReceivedReporting
import app.snapsync.data.receiving.ReceivingCounts
import app.snapsync.data.receiving.ReportedPositions
import app.snapsync.data.receiving.UnionRead
import app.snapsync.data.time.WallClock
import app.snapsync.domain.event.EventPhase
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.receiving.ForeignPhotos
import app.snapsync.domain.receiving.HeldPosition
import app.snapsync.domain.receiving.ReceivingVerdict
import app.snapsync.domain.receiving.ReportDue
import app.snapsync.domain.receiving.UnionPage
import app.snapsync.domain.receiving.UnionReadPlan
import app.snapsync.domain.receiving.UnionTrigger
import app.snapsync.inbound.Log
import app.snapsync.inbound.of

/**
 * How far this device holds the others' photos of [Membership.Active]'s event: the position a WHOLE read of its union
 * covers (decision record `changes/incremental-union`, D6 — the answer must cover the whole union up to the position it
 * names), when the membership receives nothing, or every foreign photo the read served is settled here — imported,
 * deleted by the member, or judged unimportable — and nothing waits to download or import. A doubt holds nothing: a
 * member who lacks photos must never be reported as having everything.
 */
class HoldsEverything(
    private val counts: ReceivingCounts,
    private val union: EventUnion,
    private val identity: DeviceIdentity,
) {

    @EntryPointCall
    suspend operator fun invoke(
        active: Membership.Active,
        receiving: ReceivingVerdict,
        trigger: UnionTrigger,
    ): HeldPosition =
        when (val read = union.read(active.event.id, UnionReadPlan.Whole, trigger)) {
            is UnionRead.Ok -> held(receiving, read.page)
            is UnionRead.Unavailable -> HeldPosition.Lacks
        }

    private suspend fun held(receiving: ReceivingVerdict, page: UnionPage): HeldPosition = when (receiving) {
        ReceivingVerdict.SharesOnly -> HeldPosition.ofNonReceiving(page.position)
        is ReceivingVerdict.Receives, ReceivingVerdict.KeyLost -> received(page)
    }

    private suspend fun received(page: UnionPage): HeldPosition = when (val me = identity.id()) {
        is DeviceIdRead.Ok -> facts(page, counts.held(ForeignPhotos.of(page.photos, me.value, emptySet()).refs))
        DeviceIdRead.Absent, is DeviceIdRead.Unavailable -> HeldPosition.Lacks
    }

    private fun facts(page: UnionPage, held: DownloadRead<HeldFacts>): HeldPosition = when (held) {
        is DownloadRead.Ok -> HeldPosition.of(page.position, held.value.foreign, held.value.settled, held.value.waiting)
        is DownloadRead.Unavailable -> HeldPosition.Lacks
    }
}

/**
 * Tell the event, once its range has ended, up to which union position this device holds every photo of the others —
 * a member is done once it has them, and the event's photos are deleted as soon as every member is (decision record
 * `changes/back-dated-events`, D4 and D7). Only a higher position than the last one the backend recorded in this
 * process: the backend keeps the highest. Every doubt reports nothing, and the next wake repeats.
 */
class ReportReceivedPosition(
    private val reporter: ReceivedReporting,
    private val reported: ReportedPositions,
    private val clock: WallClock,
    private val membership: MembershipRepository,
) {
    private val log = Log("ReportReceivedPosition")

    /** Whether [active]'s event has ended — only then is a position worth telling. */
    @EntryPointCall
    fun due(active: Membership.Active): Boolean = active.event.range.phaseAt(clock.now()) == EventPhase.Ended

    @EntryPointCall
    suspend operator fun invoke(active: Membership.Active, held: HeldPosition) = when (held) {
        // The read takes time — the union's is a whole one — and a join or a leave can land meanwhile: a report is only
        // ever about the membership this step found.
        is HeldPosition.Holds -> when (stillJoined(active)) {
            true -> reportedAt(active, ReportDue.of(held.position, reported.last(active.event.id)))
            false -> Unit
        }
        is HeldPosition.Lacks -> Unit
    }

    private fun stillJoined(active: Membership.Active): Boolean = when (val read = membership.state.value) {
        is MembershipRead.Joined -> read.membership.event.id == active.event.id
        is MembershipRead.NotJoined, is MembershipRead.Unavailable -> false
    }

    private suspend fun reportedAt(active: Membership.Active, due: ReportDue) = when (due) {
        is ReportDue.Report -> recorded(active, due.position, reporter.report(active.event.id, due.position))
        ReportDue.Unchanged -> Unit
    }

    private suspend fun recorded(active: Membership.Active, position: Long, reply: ReceivedReply) = when (reply) {
        ReceivedReply.Recorded -> reported.recorded(active.event.id, position).also {
            log.i("this device holds every photo of the others", "event" of active.event.id, "position" of position)
        }
        is ReceivedReply.NotRecorded -> log.w("the received position was not recorded", "why" of reply.detail)
    }
}
