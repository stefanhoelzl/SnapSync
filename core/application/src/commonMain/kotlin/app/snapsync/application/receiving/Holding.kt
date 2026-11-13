package app.snapsync.application.receiving

import app.snapsync.application.EntryPointCall
import app.snapsync.data.identity.AppDeviceIdentity
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.receiving.DeliveredPhotos
import app.snapsync.data.receiving.DownloadRead
import app.snapsync.data.receiving.EventUnion
import app.snapsync.data.receiving.HeldFacts
import app.snapsync.data.receiving.HeldRead
import app.snapsync.data.receiving.ReceivedReply
import app.snapsync.data.receiving.ReceivedReporting
import app.snapsync.data.receiving.ReceivingCounts
import app.snapsync.data.receiving.ReportedReceived
import app.snapsync.data.time.WallClock
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventPhase
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.MembershipRead
import app.snapsync.domain.receiving.DeliveredPhoto
import app.snapsync.domain.receiving.HeldPosition
import app.snapsync.domain.receiving.HeldUnion
import app.snapsync.domain.receiving.ReceivedReport
import app.snapsync.domain.receiving.ReceivingVerdict
import app.snapsync.domain.receiving.ReportDue
import app.snapsync.domain.receiving.ReportScope
import app.snapsync.domain.receiving.UnionTrigger
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log

/**
 * How far this device holds the others' photos of [Membership.Active]'s event. A membership that receives nothing is
 * owed nothing, and reads nothing to say so. One that receives holds the position a WHOLE read of its union covers
 * (decision record `changes/incremental-union`, D6 — the answer must cover the whole union up to the position it names)
 * once every foreign photo the read served is settled here — imported, deleted by the member, or judged unimportable —
 * and nothing waits to download or import. A doubt holds nothing: a member who lacks photos must never be reported as
 * having everything.
 */
class HoldsEverything(
    private val counts: ReceivingCounts,
    private val union: EventUnion,
    private val identity: AppDeviceIdentity,
) {

    @EntryPointCall
    suspend operator fun invoke(
        active: Membership.Active,
        receiving: ReceivingVerdict,
        trigger: UnionTrigger,
    ): HeldPosition = when (receiving) {
        ReceivingVerdict.SharesOnly -> HeldPosition.ReceivesNothing
        is ReceivingVerdict.Receives, ReceivingVerdict.KeyLost -> read(active, trigger)
    }

    /** The whole union, counted by ref alone: what is held needs no photo's name, so a lost key reads it too. */
    private suspend fun read(active: Membership.Active, trigger: UnionTrigger): HeldPosition =
        when (val read = union.held(active.event.id, trigger)) {
            is HeldRead.Counted -> received(read.union)
            is HeldRead.Unavailable -> HeldPosition.Lacks
        }

    private suspend fun received(union: HeldUnion): HeldPosition = when (val me = identity.id()) {
        is DeviceIdRead.Ok -> facts(union, counts.held(union.foreignTo(me.value)))
        is DeviceIdRead.Unavailable -> HeldPosition.Lacks
    }

    private fun facts(union: HeldUnion, held: DownloadRead<HeldFacts>): HeldPosition = when (held) {
        is DownloadRead.Ok ->
            HeldPosition.of(union.position, held.value.foreign, held.value.settled, held.value.waiting)
        is DownloadRead.Unavailable -> HeldPosition.Lacks
    }
}

/**
 * Tell the event what this device received ([ReceivedReport]) — a member is done once it shared everything and holds
 * what it is owed, and the event's photos are deleted as soon as every member is (decision record
 * `changes/back-dated-events`, D4 and D7). A share-only membership says at once that it receives nothing, so a phone
 * that settled early and went off holds up nobody; one that receives tells, once its event's range has ended, up to
 * which union position it holds every photo of the others — or position `0` while it lacks some, which clears a
 * "receives nothing" an earlier share-only phase left. Before the end, one that receives tells only the delivery facts
 * of the photos it added since its last report, under position `0` ([ReportScope.FACTS]), so they reach the service
 * while the event runs. Only what the backend has not recorded in this process is sent. Every doubt sends nothing, and
 * the next wake repeats.
 */
class ReportReceivedPosition(
    private val reporter: ReceivedReporting,
    private val reported: ReportedReceived,
    /** The received photos' delivery facts the service was not told yet, which the report carries. */
    private val delivered: DeliveredPhotos,
    private val clock: WallClock,
    private val membership: MembershipRepository,
) {
    private val log = Log("ReportReceivedPosition")

    /** What report on [active], which [receiving] says it receives or not, is worth telling at this wake's end. */
    @EntryPointCall
    suspend fun due(active: Membership.Active, receiving: ReceivingVerdict): ReportScope = ReceivedReport.dueFor(
        receiving,
        ended = active.event.range.phaseAt(clock.now()) == EventPhase.Ended,
        untold = untold(active.event.id).isNotEmpty(),
    )

    @EntryPointCall
    suspend operator fun invoke(active: Membership.Active, held: HeldPosition) = told(active, held.report)

    /** Only [active]'s untold delivery facts ([ReportScope.FACTS]): position `0` claims nothing held. */
    @EntryPointCall
    suspend fun facts(active: Membership.Active) = told(active, ReceivedReport.Position(0))

    /**
     * [active] receives again — a settings change turned receiving on: told at once, as position `0`, so a "receives
     * nothing" its share-only phase left never lets the event finish before this device has the photos it now wants.
     */
    @EntryPointCall
    suspend fun receivesAgain(active: Membership.Active) = told(active, ReceivedReport.Position(0))

    // The read takes time — the union's is a whole one — and a join or a leave can land meanwhile: a report is only
    // ever about the membership this step found.
    private suspend fun told(active: Membership.Active, report: ReceivedReport) = when (stillJoined(active)) {
        true -> untold(active.event.id).let { untold ->
            reportedAt(active, ReportDue.of(report, reported.last(active.event.id), untold.isNotEmpty()), untold)
        }
        false -> Unit
    }

    private fun stillJoined(active: Membership.Active): Boolean = when (val read = membership.state.value) {
        is MembershipRead.Joined -> read.membership.event.id == active.event.id
        is MembershipRead.NotJoined, is MembershipRead.Unavailable -> false
    }

    /** An untold-facts read that fails tells none: the report goes as it would have, and they wait for the next. */
    private suspend fun untold(event: EventId): List<DeliveredPhoto> = when (val read = delivered.untold(event)) {
        is DownloadRead.Ok -> read.value
        is DownloadRead.Unavailable -> emptyList<DeliveredPhoto>().also {
            log.w("the received photos' delivery facts were not read", "why" of read.detail)
        }
    }

    private suspend fun reportedAt(active: Membership.Active, due: ReportDue, untold: List<DeliveredPhoto>) =
        when (due) {
            is ReportDue.Report ->
                recorded(active, due.report, untold, reporter.report(active.event.id, due.report, untold))
            ReportDue.Unchanged -> Unit
        }

    private suspend fun recorded(
        active: Membership.Active,
        report: ReceivedReport,
        untold: List<DeliveredPhoto>,
        reply: ReceivedReply,
    ) =
        when (reply) {
            ReceivedReply.Recorded -> reported.recorded(active.event.id, report).also {
                delivered.told(untold)
                log.i("the event knows what it received", "event" of active.event.id, "report" of report.text())
            }
            is ReceivedReply.NotRecorded -> log.w("what this device received was not recorded", "why" of reply.detail)
        }

    /** A report as the log names it. */
    private fun ReceivedReport.text(): String = when (this) {
        is ReceivedReport.Position -> "position $position"
        ReceivedReport.ReceivesNothing -> "receives nothing"
    }
}
