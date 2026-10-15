package app.snapsync.application.membership

import app.snapsync.application.EntryPointCall
import app.snapsync.data.membership.EventCheck
import app.snapsync.data.membership.EventCheckRecord
import app.snapsync.data.time.WallClock
import app.snapsync.domain.event.EventPhase
import app.snapsync.domain.membership.CheckCadence
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.time.Timestamp

/**
 * Claim a read of the server about the joined event's state at the end of a wake (decision record
 * `changes/timely-background-receiving`, D5): only once its range has ended — nobody can be done with it before — and,
 * for a wake that is bounded (not a push, an opening or a join), at most once an hour. A claimed read is stamped
 * first, so two wakes in a row do not both read.
 */
class ClaimStateCheck(private val record: EventCheckRecord, private val clock: WallClock) {
    @EntryPointCall
    operator fun invoke(active: Membership.Active, bounded: Boolean): CheckCadence {
        val now = clock.now()
        return when (active.event.range.phaseAt(now)) {
            EventPhase.Ended -> claimed(active, bounded, now)
            EventPhase.Upcoming, EventPhase.Running -> CheckCadence.NotYet
        }
    }

    private fun claimed(active: Membership.Active, bounded: Boolean, now: Timestamp): CheckCadence {
        val cadence = when (bounded) {
            true -> CheckCadence.of(record.last(EventCheck.STATE, active.event.id), now)
            false -> CheckCadence.Due
        }
        return cadence.also { stamped(it, active, now) }
    }

    private fun stamped(cadence: CheckCadence, active: Membership.Active, now: Timestamp) = when (cadence) {
        // A stamp that cannot be written lets the next wake read again: at worst one read more.
        CheckCadence.Due -> record.stamp(EventCheck.STATE, active.event.id, now).let { }
        CheckCadence.NotYet -> Unit
    }
}
