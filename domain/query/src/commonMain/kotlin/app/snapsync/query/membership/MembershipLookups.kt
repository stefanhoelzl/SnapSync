package app.snapsync.query.membership

import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.event.EventDirectory
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.time.WallClock
import app.snapsync.domain.event.Event
import app.snapsync.domain.event.EventLookupFact
import app.snapsync.domain.event.EventPhase
import app.snapsync.domain.event.KeyVerdict
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.MembershipRead
import app.snapsync.domain.membership.SwitchFacts
import app.snapsync.domain.service.ServiceFault
import app.snapsync.query.ScreenLookup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/** The membership as this process holds it — re-read at every entry. */
class MembershipQuery(private val membership: MembershipRepository) {
    val state: StateFlow<MembershipRead> get() = membership.state

    /**
     * The membership as a screen shows it: held, none, or not readable since this process started. Distinct, and as
     * current as [state] at every read; building it reads nothing.
     */
    val shown: StateFlow<MembershipShown> = ShownFlow(membership)

    /** The active membership held, or `null` when none is — a deleted one, none at all, or one that cannot be read. */
    fun active(): Membership.Active? = when (val read = state.value) {
        is MembershipRead.Joined -> read.membership as? Membership.Active
        is MembershipRead.NotJoined, is MembershipRead.Unavailable -> null
    }
}

/**
 * What a screen shows of the membership — the one read a screen takes of it, so whether it is held and whether it can
 * be read never reach the screen apart.
 */
sealed interface MembershipShown {
    /** The membership held, or `null` while none is. */
    val held: Membership?

    data class Held(override val held: Membership) : MembershipShown

    /**
     * None held: not joined — or a row this build refuses, which reads the same however often it is read, so a join is
     * the member's way back.
     */
    data object None : MembershipShown {
        override val held: Membership? get() = null
    }

    /** Not readable, and not read since this process started: whether the device is in an event is not known. */
    data object Unreadable : MembershipShown {
        override val held: Membership? get() = null
    }
}

/**
 * The membership's read as a screen shows it: re-derived at every read, emitted only when it changes. The store's
 * state is reached only when read, since its first read opens the shared database.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class ShownFlow(private val membership: MembershipRepository) : StateFlow<MembershipShown> {
    override val value: MembershipShown get() = membership.state.value.shown()
    override val replayCache: List<MembershipShown> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<MembershipShown>): Nothing {
        var told: Any? = NothingTold
        membership.state.collect { read ->
            val shown = read.shown()
            if (shown != told) collector.emit(shown)
            told = shown
        }
    }

    private fun MembershipRead.shown(): MembershipShown = when (this) {
        is MembershipRead.Joined -> MembershipShown.Held(this.membership)
        is MembershipRead.NotJoined -> MembershipShown.None
        is MembershipRead.Unavailable -> if (refused) MembershipShown.None else MembershipShown.Unreadable
    }

    /** What was told before anything was: unequal to every membership and to none. */
    private object NothingTold
}

/**
 * The join screen's read of an invite's event: found — and then whether the invite's key opens it, so a link cut short
 * in sharing is told before the member chooses anything — not found, or no answer.
 */
class JoinDetailsLookup(
    private val directory: EventDirectory,
    private val ids: EventKeyIds,
    lane: CoroutineDispatcher,
) : ScreenLookup<InviteLink, JoinDetails>("query.joinDetails", lane) {
    override suspend fun read(arg: InviteLink): JoinDetails = when (val found = directory.lookup(arg.event)) {
        is EventLookupFact.Found -> keyed(arg, found)
        EventLookupFact.NotFound -> JoinDetails.NotFound
        is EventLookupFact.Unanswered -> JoinDetails.Unanswered(found.fault, found.retryAfterSeconds)
    }

    override fun about(arg: InviteLink): String = "eventId=${arg.event}"

    private fun keyed(invite: InviteLink, found: EventLookupFact.Found): JoinDetails =
        when (found.event.encryption.openedBy(invite.key?.let(ids::identify))) {
            is KeyVerdict.Opens -> JoinDetails.Found(found.event, found.completed)
            KeyVerdict.WrongKey -> JoinDetails.WrongLink
        }
}

/** What the join screen found for an invite. */
sealed interface JoinDetails {
    /** [event] as the server holds it; [completed] when its photos are deleted already. */
    data class Found(val event: Event, val completed: Boolean) : JoinDetails

    /** The invite's key does not open the event. */
    data object WrongLink : JoinDetails

    data object NotFound : JoinDetails

    /** No answer: [fault] is how the service failed; [retryAfterSeconds], while it is updated, when to ask again. */
    data class Unanswered(val fault: ServiceFault, val retryAfterSeconds: Long? = null) : JoinDetails
}

/**
 * What the server says of the joined membership when another event's invite opens (decision record
 * `changes/back-dated-events`, D8): one read, and only of an event that has ended; every doubt is undecided.
 */
class SwitchLookup(
    private val membership: MembershipRepository,
    private val directory: EventDirectory,
    private val clock: WallClock,
) {
    suspend fun facts(): SwitchFacts = when (val held = membership.read()) {
        is MembershipRead.Joined -> held(held.membership)
        is MembershipRead.NotJoined, is MembershipRead.Unavailable -> SwitchFacts.Undecided
    }

    private suspend fun held(held: Membership): SwitchFacts = when (held) {
        is Membership.Deleted -> SwitchFacts.Deleted
        is Membership.Active -> when (held.event.range.phaseAt(clock.now())) {
            EventPhase.Ended -> served(directory.lookup(held.event.id))
            EventPhase.Upcoming, EventPhase.Running -> SwitchFacts.Undecided
        }
    }

    private fun served(lookup: EventLookupFact): SwitchFacts = when (lookup) {
        is EventLookupFact.Found -> SwitchFacts.served(lookup.event, lookup.completed)
        EventLookupFact.NotFound, is EventLookupFact.Unanswered -> SwitchFacts.Undecided
    }
}
