package app.snapsync.domain.membership

import app.snapsync.domain.event.Event

/**
 * What opening another event's invite does to the joined membership: [Ask] whether to leave it, leave it [Quiet]ly when
 * leaving loses nothing for anyone, or [Stay] when the invite cannot be joined — an invite that is not joinable never
 * causes a leave.
 */
sealed interface SwitchVerdict {
    data object Ask : SwitchVerdict

    data object Quiet : SwitchVerdict

    data object Stay : SwitchVerdict

    companion object {
        /**
         * The verdict for [invite] while [current] is joined. Leaving loses nothing when its photos are deleted, or
         * when it has ended, every member still in it has shared everything and this device holds every photo of the
         * others ([holdsEverything]); any doubt asks.
         */
        fun of(current: SwitchFacts, holdsEverything: Boolean?, invite: InviteFact): SwitchVerdict = when (invite) {
            InviteFact.NotJoinable -> Stay
            InviteFact.Unknown -> Ask
            InviteFact.Joinable -> when (current.losesNothing(holdsEverything)) {
                true -> Quiet
                false -> Ask
            }
        }
    }
}

/** What the server says of the joined membership, as far as a switch cares. */
sealed interface SwitchFacts {

    /** Whether leaving it loses nothing, given whether this device holds every photo of the others. */
    fun losesNothing(holdsEverything: Boolean?): Boolean

    /** Whether that needs asking: only of an ended event every member has shared. */
    val needsReceivedCheck: Boolean

    /** Its photos are deleted — stored so, or confirmed on this very read. */
    data object Deleted : SwitchFacts {
        override fun losesNothing(holdsEverything: Boolean?) = true
        override val needsReceivedCheck = false
    }

    /** It is on, or the server could not tell: nothing says that leaving loses nothing. */
    data object Undecided : SwitchFacts {
        override fun losesNothing(holdsEverything: Boolean?) = false
        override val needsReceivedCheck = false
    }

    /** It has ended and every member still in it has shared everything: only what this device holds is left to ask. */
    data object EveryoneShared : SwitchFacts {
        override fun losesNothing(holdsEverything: Boolean?) = holdsEverything == true
        override val needsReceivedCheck = true
    }

    companion object {
        /**
         * What the server's read of the ended joined [event] says: its photos deleted on this very read when
         * [completed] — nothing is left on the server to lose — else everyone shared only when it counts no member it
         * still waits for. A read that counts no members decides nothing.
         */
        fun served(event: Event, completed: Boolean): SwitchFacts = when {
            completed -> Deleted
            event.members?.waiting == 0 -> EveryoneShared
            else -> Undecided
        }
    }
}

/** Whether the invite opened can be joined, as its event's read answered. */
enum class InviteFact {
    /** Found, and the invite's key opens it. */
    Joinable,

    /** Missing, deleted, or the invite's key does not open it. */
    NotJoinable,

    /** The read could not tell. */
    Unknown,
}
