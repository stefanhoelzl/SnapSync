package app.snapsync.application.receiving

import app.snapsync.application.EntryPointCall
import app.snapsync.data.identity.AppDeviceIdentity
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.membership.EventCheck
import app.snapsync.data.membership.EventCheckRecord
import app.snapsync.data.receiving.DownloadRead
import app.snapsync.data.receiving.EventUnion
import app.snapsync.data.receiving.ReceivingPlans
import app.snapsync.data.receiving.StoredPosition
import app.snapsync.data.receiving.UnionRead
import app.snapsync.data.time.WallClock
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.membership.CheckCadence
import app.snapsync.domain.receiving.ReceivingMembership
import app.snapsync.domain.receiving.UnionPage
import app.snapsync.domain.receiving.UnionReadPlan
import app.snapsync.domain.receiving.UnionTrigger
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log

/**
 * Plan what [ReceivingMembership]'s event's union serves of the others: read it — a delta from the stored position for
 * a push or a background wake, the whole union otherwise (decision record `changes/incremental-union`, D6), and
 * nothing when a push announced a position already read — record its open foreign photos and start their transfers.
 * Idempotent: settled and planned photos are no-ops, and only resources not yet staged are fetched.
 *
 * Every read counts toward the hourly bound ([PlanIfDue]), a failing one too: a backend that fails is asked no more
 * often than one that answers. It imports nothing: a failed read costs a wake its discovery, never its imports, which
 * the wake's import sequence drains from what is staged whatever the union answered.
 */
class PlanReceiving(
    private val plans: ReceivingPlans,
    private val union: EventUnion,
    private val identity: AppDeviceIdentity,
    private val checks: EventCheckRecord,
    private val clock: WallClock,
) {
    private val log = Log("PlanReceiving")

    /**
     * Plan for [trigger] — from [announced], the position a push named, or from [known], the whole union a caller read
     * in this same act (a join's recognition, seconds before), which no second read repeats.
     */
    @EntryPointCall
    suspend operator fun invoke(
        receiving: ReceivingMembership,
        trigger: UnionTrigger,
        announced: Long? = null,
        known: UnionPage? = null,
    ): PlanOutcome = logged(Planning(receiving.membership.event.id, trigger, announced, known).planned())

    /** One plan of [event]'s union. */
    private inner class Planning(
        private val event: EventId,
        private val trigger: UnionTrigger,
        private val announced: Long?,
        private val known: UnionPage?,
    ) {
        suspend fun planned(): PlanOutcome = when (val stored = plans.position(event)) {
            is DownloadRead.Ok -> readFor(stored.value)
            is DownloadRead.Unavailable -> PlanOutcome.Unavailable(stored.detail)
        }

        private suspend fun readFor(stored: StoredPosition): PlanOutcome = when (val plan = planOf(stored)) {
            UnionReadPlan.AlreadyRead -> PlanOutcome.NothingNew
            is UnionReadPlan.Reading -> read(plan, stored)
        }

        /** What a caller read in this same act is a whole read: no second one repeats it. */
        private fun planOf(stored: StoredPosition): UnionReadPlan = when (known) {
            null -> UnionReadPlan.of(trigger.whole, stored.position, announced)
            else -> UnionReadPlan.Whole
        }

        private suspend fun read(plan: UnionReadPlan.Reading, stored: StoredPosition): PlanOutcome = when (
            val page = pageOf(
                plan,
            )
        ) {
            is UnionRead.Ok -> identified(page.page, plan, stored)
            is UnionRead.Unavailable -> PlanOutcome.UnionUnavailable(page.detail)
        }

        /** Either way the hour is stamped: a union was read for this event now — by this act or by the caller's. */
        private suspend fun pageOf(plan: UnionReadPlan.Reading): UnionRead {
            checks.stamp(EventCheck.PHOTOS, event, clock.now())
            return known?.let(UnionRead::Ok) ?: union.read(event, plan, trigger)
        }

        private suspend fun identified(page: UnionPage, plan: UnionReadPlan.Reading, stored: StoredPosition) =
            when (val me = identity.id()) {
                is DeviceIdRead.Ok -> recorded(page, plan, me.value, stored)
                is DeviceIdRead.Unavailable -> PlanOutcome.Unavailable("this device's id: $me")
            }

        private suspend fun recorded(
            page: UnionPage,
            plan: UnionReadPlan.Reading,
            me: DeviceId,
            stored: StoredPosition,
        ) =
            when (val planned = plans.plan(event, page, plan, me, stored)) {
                is DownloadRead.Ok -> PlanOutcome.Planned(planned.value, page.position)
                is DownloadRead.Unavailable -> PlanOutcome.Unavailable(planned.detail)
            }
    }

    private suspend fun logged(outcome: PlanOutcome): PlanOutcome {
        when (outcome) {
            is PlanOutcome.Planned -> log.i(
                "planned",
                "open" of outcome.planned.planned,
                "pending" of outcome.planned.pending,
                "freed" of outcome.planned.pruned,
                "position" of outcome.position,
            )
            PlanOutcome.NothingNew -> log.i("the announced position is already read")
            // A failed read keeps the last good state; the wake's import sequence still drains what is staged.
            is PlanOutcome.UnionUnavailable -> log.w("the union could not be read", "why" of outcome.detail)
            is PlanOutcome.Unavailable -> log.w("nothing was planned", "why" of outcome.detail)
        }
        return outcome
    }
}

/**
 * [PlanReceiving] for a background wake, unless the union was read within the hour (decision record
 * `changes/timely-background-receiving`, D4): new photos are announced by a silent wake, and never only by it, so a
 * background wake reads too — at no more than one read an hour per event. A push, an opening and a join plan
 * themselves: each has a reason to read now.
 */
class PlanIfDue(private val checks: EventCheckRecord, private val clock: WallClock) {
    @EntryPointCall
    operator fun invoke(receiving: ReceivingMembership): CheckCadence =
        CheckCadence.of(checks.last(EventCheck.PHOTOS, receiving.membership.event.id), clock.now())
}

/** What a plan came to. */
sealed interface PlanOutcome {
    class Planned(val planned: app.snapsync.data.receiving.Planned, val position: Long) : PlanOutcome

    /** A push announced a position this device has already read. */
    data object NothingNew : PlanOutcome

    /** The union could not be read: the last good state stands. */
    class UnionUnavailable(val detail: String) : PlanOutcome

    /** The store or the device's id could not be read. */
    class Unavailable(val detail: String) : PlanOutcome
}
