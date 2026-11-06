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
     * The membership held, or `null` while none is as far as this process can tell — not joined, or not readable now:
     * what a screen shows either way. Distinct, and as current as [state] at every read; building it reads nothing.
     */
    val joined: StateFlow<Membership?> = JoinedFlow(membership)

    /** The active membership held, or `null` when none is — a deleted one, none at all, or one that cannot be read. */
    fun active(): Membership.Active? = when (val read = state.value) {
        is MembershipRead.Joined -> read.membership as? Membership.Active
        is MembershipRead.NotJoined, is MembershipRead.Unavailable -> null
    }
}

/**
 * The membership's read as the membership held: re-derived at every read, emitted only when it changes. The store's
 * state is reached only when read, since its first read opens the shared database.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class JoinedFlow(private val membership: MembershipRepository) : StateFlow<Membership?> {
    override val value: Membership? get() = membership.state.value.held()
    override val replayCache: List<Membership?> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<Membership?>): Nothing {
        var told: Any? = NothingTold
        membership.state.collect { read ->
            val held = read.held()
            if (held != told) collector.emit(held)
            told = held
        }
    }

    private fun MembershipRead.held(): Membership? = (this as? MembershipRead.Joined)?.membership

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
