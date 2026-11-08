package app.snapsync.application.membership

import app.snapsync.application.EntryPointCall
import app.snapsync.data.membership.EnterResult
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.domain.membership.Enrolled
import app.snapsync.domain.membership.JoinCommit
import app.snapsync.domain.membership.Membership

/** Enter the membership the server admitted this device to: the row that makes this device joined. */
class EnterMembership(private val membership: MembershipRepository) {
    @EntryPointCall
    operator fun invoke(enrolled: Enrolled): EnterOutcome = when (val entered = membership.enter(enrolled)) {
        is EnterResult.Entered -> EnterOutcome.Entered(entered.active)
        EnterResult.AlreadyJoined -> EnterOutcome.AlreadyJoined
        EnterResult.JoinedElsewhere, is EnterResult.Failed -> EnterOutcome.Failed
    }
}

/** What entering came to. */
sealed interface EnterOutcome {
    data class Entered(val active: Membership.Active) : EnterOutcome

    /** Nothing was entered here; [answer] is what the member is told. */
    sealed interface NotEntered : EnterOutcome {
        val answer: JoinCommit
    }

    /** Joined already — another road entered it first. */
    data object AlreadyJoined : NotEntered {
        override val answer = JoinCommit.Committed
    }

    /** Nothing was written. The server's enrolment stands: a join is retried, never undone. */
    data object Failed : NotEntered {
        override val answer = JoinCommit.Failed()
    }
}
