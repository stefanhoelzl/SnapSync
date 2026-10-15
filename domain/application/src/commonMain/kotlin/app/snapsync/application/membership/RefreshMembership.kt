package app.snapsync.application.membership

import app.snapsync.application.EntryPointCall
import app.snapsync.data.event.EventDirectory
import app.snapsync.data.membership.MarkResult
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.UpdateResult
import app.snapsync.data.time.WallClock
import app.snapsync.domain.membership.DeletionVerdict
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.MembershipUpdate

/**
 * Bring the active membership up to the server's word: its photos confirmed deleted marks it so — it stays, shown as
 * over, and nothing more is shared or received; otherwise its name, members and open-until date follow the server.
 * Whatever the server cannot tell changes nothing.
 */
class RefreshMembership(
    private val membership: MembershipRepository,
    private val directory: EventDirectory,
    private val clock: WallClock,
) {
    @EntryPointCall
    suspend operator fun invoke(active: Membership.Active): RefreshOutcome {
        val lookup = directory.lookup(active.event.id)
        return when (val verdict = DeletionVerdict.of(active.event, lookup, clock.now())) {
            is DeletionVerdict.Confirmed -> marked(membership.markDeleted(active, verdict.evidence))
            is DeletionVerdict.NotDeleted -> updated(active, MembershipUpdate.of(active.event, verdict.served))
            DeletionVerdict.Inconclusive -> RefreshOutcome.Inconclusive
        }
    }

    private fun marked(marked: MarkResult): RefreshOutcome = when (marked) {
        is MarkResult.Marked -> RefreshOutcome.Deleted(marked.deleted)
        MarkResult.Superseded -> RefreshOutcome.Superseded
        is MarkResult.Failed -> RefreshOutcome.Inconclusive
    }

    private fun updated(active: Membership.Active, update: MembershipUpdate): RefreshOutcome = when (update) {
        is MembershipUpdate.Changed -> written(active, membership.update(active, update.event))
        MembershipUpdate.Unchanged -> RefreshOutcome.Current(active)
    }

    private fun written(active: Membership.Active, written: UpdateResult): RefreshOutcome = when (written) {
        is UpdateResult.Updated -> RefreshOutcome.Current(written.active)
        UpdateResult.Superseded -> RefreshOutcome.Superseded
        // The next refresh converges it again: nothing a member relies on waits for this one.
        is UpdateResult.Failed -> RefreshOutcome.Current(active)
    }
}

/** What a refresh came to. */
sealed interface RefreshOutcome {
    /** The server confirmed the photos deleted: the membership is marked so. */
    data class Deleted(val deleted: Membership.Deleted) : RefreshOutcome

    /** Still on the server, as [active] now holds it. */
    data class Current(val active: Membership.Active) : RefreshOutcome

    /** The server could not tell, or the mark could not be written: nothing changed. */
    data object Inconclusive : RefreshOutcome

    /** The membership changed meanwhile — a switch, a leave, another deletion: nothing was written. */
    data object Superseded : RefreshOutcome
}
