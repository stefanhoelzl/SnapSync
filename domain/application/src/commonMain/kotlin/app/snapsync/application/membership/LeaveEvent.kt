package app.snapsync.application.membership

import app.snapsync.application.EntryPointCall
import app.snapsync.data.membership.LeaveResult
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.domain.membership.LeftMembership

/**
 * Leave the joined event — all or nothing: the membership ends and its leave is owed to the backend together, or the
 * member stays fully joined. Nothing is torn down here; the evidence it answers is what lets the rest be.
 */
class LeaveEvent(private val membership: MembershipRepository) {
    @EntryPointCall
    operator fun invoke(): LeaveOutcome = when (val left = membership.leave()) {
        is LeaveResult.Left -> LeaveOutcome.Left(left.evidence)
        LeaveResult.NotJoined -> LeaveOutcome.NotJoined
        is LeaveResult.Failed -> LeaveOutcome.Failed
    }
}

/** What a leave came to. */
sealed interface LeaveOutcome {
    class Left(val evidence: LeftMembership) : LeaveOutcome

    /** Nothing was left. */
    sealed interface NotLeft : LeaveOutcome

    /** There was nothing to leave. */
    data object NotJoined : NotLeft

    /** Nothing changed: the member is fully joined. */
    data object Failed : NotLeft
}
