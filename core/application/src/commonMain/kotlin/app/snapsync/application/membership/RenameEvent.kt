package app.snapsync.application.membership

import app.snapsync.application.EntryPointCall
import app.snapsync.data.event.EventRename
import app.snapsync.data.event.RenameFact
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventName
import app.snapsync.domain.event.EventNameVerdict
import app.snapsync.domain.membership.DeletionVerdict
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.RenameResult
import app.snapsync.domain.service.ServiceFault

/**
 * Rename the joined event for every member. A name that is none is refused before anything is sent; the name kept is
 * the one the server stored. Only the server's positive word that the event's photos were deleted marks the membership
 * so — it stays, shown as over; a "not found" is one witness, and a rename is no road to a deletion.
 */
class RenameEvent(private val membership: MembershipRepository, private val rename: EventRename) {
    @EntryPointCall
    suspend operator fun invoke(active: Membership.Active, event: EventId, name: String): RenameOutcome =
        when (event == active.event.id) {
            true -> named(active, name)
            // The rename was asked of a membership a switch replaced meanwhile.
            false -> RenameOutcome.NotCurrent
        }

    private suspend fun named(active: Membership.Active, name: String): RenameOutcome =
        when (val parsed = EventName.of(name)) {
            is EventNameVerdict.Ok -> renamed(active, rename.rename(active.event.id, parsed.name))
            EventNameVerdict.Blank, EventNameVerdict.TooLong -> RenameOutcome.InvalidName
        }

    private fun renamed(active: Membership.Active, fact: RenameFact): RenameOutcome = when (fact) {
        // The server holds the name now: a membership that could not take it converges at the next refresh.
        is RenameFact.Renamed -> RenameOutcome.Renamed(fact.name).also {
            membership.update(active, active.event.copy(name = fact.name))
        }
        RenameFact.EventOver -> over(active)
        is RenameFact.NotRenamed -> unrenamed(fact)
    }

    /**
     * The event is over: marked so. One that cannot be written is the next refresh's to mark — the member is told
     * either way.
     */
    private fun over(active: Membership.Active): RenameOutcome {
        membership.markDeleted(active, DeletionVerdict.answeredOver(active.event).evidence)
        return RenameOutcome.EventOver
    }

    private fun unrenamed(fact: RenameFact.NotRenamed): RenameOutcome = when (fact) {
        RenameFact.Refused -> RenameOutcome.InvalidName
        is RenameFact.Unanswered -> RenameOutcome.Failed(fact.fault)
    }
}

/** What a rename came to. */
sealed interface RenameOutcome {
    /** What the member is told. */
    val answer: RenameResult

    data class Renamed(val name: EventName) : RenameOutcome {
        override val answer = RenameResult.Renamed
    }

    /** No name the server takes: nothing changed. */
    data object InvalidName : RenameOutcome {
        override val answer = RenameResult.InvalidName
    }

    /** No answer: nothing changed. [fault] is how the service failed — `null` for an answer from a working one. */
    data class Failed(val fault: ServiceFault?) : RenameOutcome {
        override val answer = RenameResult.Failed(fault)
    }

    /** The event's photos are deleted: the membership is marked so, and nothing was renamed. */
    data object EventOver : RenameOutcome {
        override val answer = RenameResult.EventOver
    }

    /** The membership asked about is no longer the active one: nothing was sent. */
    data object NotCurrent : RenameOutcome {
        override val answer = RenameResult.Failed()
    }
}
