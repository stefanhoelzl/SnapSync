package app.snapsync.ui

import app.snapsync.model.DeletesAt
import app.snapsync.model.EventEnd
import app.snapsync.model.EventStart
import app.snapsync.presentation.JoinPhase

// Reading the event's window off a [JoinPhase] (capability `join-event`). Its own file because it is
// neither of the two things `RangeSelection.kt` holds — it is how ONE of the two surfaces finds the
// window that the shared rules then resolve against; the reconfigure surface reads its own off the
// persisted membership and never comes here.

/**
 * The event's start, from whichever phase carries it (capability `photo-selection-policy`).
 *
 * `null` on the phases that carry none (`Loading` before the fetch resolves; `NotFound`/`LoadFailed`),
 * which is why the cutoff row seeds from the first phase that *does* carry one, rather than from
 * whichever phase the screen happened to mount at.
 *
 * Unlike the seed it replaces, this covers **Committing and CommitFailed too**. Those phases carry
 * `startsAt` precisely because a Retry commits WITHOUT passing back through the loaded phase — reading it
 * only from `Ready` would make a retry derive its cutoff from `now` instead of the start the user chose,
 * silently discarding their selection at the one moment they are already recovering from a failure.
 */
internal fun JoinPhase.startsAt(): EventStart? = when (this) {
    is JoinPhase.ExplainAccess -> startsAt
    is JoinPhase.Ready -> startsAt
    is JoinPhase.Committing -> startsAt
    is JoinPhase.CommitFailed -> startsAt
    JoinPhase.Loading, JoinPhase.NotFound, JoinPhase.LoadFailed -> null
}

/**
 * The event's end, from whichever phase carries it (capability `photo-selection-policy`) — the range's
 * upper **default** and its **ceiling**. Carried by the same four phases as [startsAt], and for the same
 * reason: a Retry commits without passing back through the loaded phase, so the ceiling has to still be
 * here or the retry would derive its upper bound from a phase that lost it.
 */
internal fun JoinPhase.endsAt(): EventEnd? = when (this) {
    is JoinPhase.ExplainAccess -> endsAt
    is JoinPhase.Ready -> endsAt
    is JoinPhase.Committing -> endsAt
    is JoinPhase.CommitFailed -> endsAt
    JoinPhase.Loading, JoinPhase.NotFound, JoinPhase.LoadFailed -> null
}

/**
 * The event's **retention deadline**, from whichever phase carries it (capability `event-limits`) — when
 * the shared photos are deleted. Server-derived and carried verbatim; never computed here, because a
 * client-side copy of the retention rule would promise a date the backend will not honour, silently.
 *
 * Carried by the same four phases as [startsAt]/[endsAt] for the same reason — a Retry commits without
 * passing back through the loaded phase, and the commit persists this value as the offline witness of the
 * self-leave (capability `leave-event`).
 */
internal fun JoinPhase.deletesAt(): DeletesAt? = when (this) {
    is JoinPhase.ExplainAccess -> deletesAt
    is JoinPhase.Ready -> deletesAt
    is JoinPhase.Committing -> deletesAt
    is JoinPhase.CommitFailed -> deletesAt
    JoinPhase.Loading, JoinPhase.NotFound, JoinPhase.LoadFailed -> null
}
