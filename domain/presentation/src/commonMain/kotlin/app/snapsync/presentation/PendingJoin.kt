package app.snapsync.presentation

import app.snapsync.model.DeletesAt
import app.snapsync.model.EventEnd
import app.snapsync.model.EventStart
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.ScreenMessage

/**
 * An in-progress interactive join/switch confirmation: the event being
 * joined and how far its confirmation has got ([phase]). The reducer shows a non-null value as
 * `Layer.JoiningEvent` (config absent) or `Joined.pendingSwitch` (config present); `null` means no
 * confirmation is open.
 *
 * Held in a `MutableStateFlow` ([StatusSources.pending]) that the container both reads and writes: its gate
 * methods advance the [phase], and the reduction reads it. A test may inject one to start the gate at any
 * [JoinProgress].
 */
data class PendingJoin(
    val eventId: String,
    val phase: JoinProgress,
    /** The key the invite link carried, `null` when it carried none — committed with the join, shown nowhere. */
    val linkKey: String? = null,
    /** The loaded event's key id, once its details loaded — what [linkKey] was checked against. */
    val eventKeyId: String? = null,
) {
    /** Never the key: a pending join reaches logs and test failures. */
    override fun toString(): String = "PendingJoin(eventId=$eventId, phase=$phase, linkKey=${linkKey?.let { "present" }})"
}

/**
 * How far a join confirmation has got — the confirmation's own record, which the screen
 * sees only as the [JoinPhase] it [shows].
 *
 * Four phases carry no event: the fetch has not resolved, or there is nothing to be invited to. Every other phase is
 * [Detailed] — the fetched facts plus which step of the confirmation is showing — so **a step that commits the event's
 * facts cannot be constructed without them**: a Retry commits without passing back through [JoinPhase.Detailed.Step.Ready].
 */
sealed interface JoinProgress {
    /** The phase as the screen shows it. */
    val shows: JoinPhase

    data object Loading : JoinProgress {
        override val shows: JoinPhase get() = JoinPhase.Loading
    }

    data object NotFound : JoinProgress {
        override val shows: JoinPhase get() = JoinPhase.NotFound
    }

    data object LoadFailed : JoinProgress {
        override val shows: JoinPhase get() = JoinPhase.LoadFailed
    }

    data object Closed : JoinProgress {
        override val shows: JoinPhase get() = JoinPhase.Closed
    }

    data object WrongLink : JoinProgress {
        override val shows: JoinPhase get() = JoinPhase.WrongLink
    }

    /** Details loaded: [event] is what was fetched, [step] is where in the confirmation the member is. */
    data class Detailed(
        val event: EventDetails,
        val step: JoinPhase.Detailed.Step,
        /** Why the service refused this phone, on [JoinPhase.Detailed.Step.DeviceRefused] only — see [JoinPhase.Detailed.refusal]. */
        val refusal: ScreenMessage? = null,
    ) : JoinProgress {
        override val shows: JoinPhase get() = JoinPhase.Detailed(event.name, step, refusal, event.members)
    }
}

/**
 * The event facts a join confirmation renders and commits.
 *
 * [startsAt] is the event's **start date** — already a canonical UTC `…Z` string (`BackendEventDirectory`
 * normalizes it and fails the load rather than invent one). It is the range's lower **default**, and with [endsAt]
 * the event range a chosen one must overlap — at most an event's length, so joining at whole-library scope is
 * unrepresentable. It also decides whether "From now" is offered — only while now lies inside the event's range.
 *
 * [endsAt] is the event's **end date** — the range's upper **default**.
 *
 * [deletesAt] is the event's **retention deadline** — **server-derived** and carried verbatim. The commit persists
 * it as the offline witness of the self-leave. It is never computed on the device: a client-side copy of the
 * retention constant would
 * promise a date the backend will not honour, silently.
 */
data class EventDetails(
    val name: String,
    val startsAt: EventStart,
    val endsAt: EventEnd,
    val deletesAt: DeletesAt,
    /** How many members the event had when its details were read — the join screen shows it; `null` if not served. */
    val members: Int? = null,
    /** The day guests may join until, as served; the commit persists it so the joined screen shows it at once. */
    val openUntil: EventEnd? = null,
)
