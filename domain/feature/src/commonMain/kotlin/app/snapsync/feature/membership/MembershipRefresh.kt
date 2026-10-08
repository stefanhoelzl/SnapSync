package app.snapsync.feature.membership

import app.snapsync.model.JoinLoad
import app.snapsync.model.confirmedGone
import app.snapsync.services.config.ConfigService

/**
 * The membership-refresh **rule** (capability `join-event`): fold a freshly fetched event-details result
 * into the persisted membership, and say what happened.
 *
 * One job, named for the need it serves rather than for any single field it touches — reconcile the
 * persisted membership against fresh details. (It was `EventName` when the name was all it refreshed;
 * the window backfill, the retention backfill, and the absence verdict are the same operation seen from
 * three sides, so the name moved with the job.)
 *
 * The *fetch* stays coordination in **one** `flow/` trigger — `Foreground`, which keeps the membership
 * current — over a `compose/`-built `EventDirectory` effect; this feature owns the decision of what the
 * fetched value means. (`Provision` once fetched too, to fill a name a scan couldn't fetch while
 * offline. A membership can no longer arrive nameless, and every provision route has just loaded or
 * minted its details, so that fetch was redundant by construction and is gone.) [RefreshOutcome] is what the
 * rule ANSWERS, not what the flows branch on: the one destructive consequence is performed here (see
 * [leaveEvent]), so no trigger can reach it by a different route.
 *
 * Seated in `feature/membership` because the membership config is this feature's durable state
 * (one-writer: join/provision saves it, leave clears it, and this refresh rewrites it whole).
 */
class MembershipRefresh(
    private val configSource: ConfigService,
    /**
     * The ordinary local teardown (capability `manage-membership`), performed on a confirmed absence.
     *
     * A SIBLING of this rule inside `feature/membership`, so referencing it directly is not a
     * feature-blindness breach — and the consequence belongs with the decision. It lives here rather than
     * as a `when` in the flow because the flow transcriber's closed grammar admits no `when` inside an
     * escaping `scope.launch` (specs `docs/architecture.md` / `docs/architecture.md`), and its own remedy
     * is to sink the rule into a feature. Doing so also means every trigger reaches the same consequence
     * by construction: one verdict cannot mean two things depending on which flow observed it.
     */
    private val leaveEvent: LeaveEvent,
) {

    /**
     * Fold a freshly [fetched] details result into the persisted membership and return what it means.
     *
     * - [RefreshOutcome.REFRESHED] — the fetch resolved for the still-configured event. Two rewrites ride
     *   together in **one** whole-config save: **name convergence** (an unchanged name saves nothing), and
     *   the **window + retention backfill** (capability `photo-sharing`) filling the
     *   event's `endsAt` and `deletesAt` — each only when ABSENT. Doing them in one save is what stops
     *   the rewrites from losing each other's field. The membership's own `maxPhotoDate` is **not**
     *   backfilled: it is required on every persisted membership (capability `join-event`), so a config
     *   that decoded at all already carries one.
     * - [RefreshOutcome.CLOSED] — the same, for a membership whose event has closed (now or before).
     * - [RefreshOutcome.INCONCLUSIVE] — the fetch could not tell (offline, transport, non-404 status,
     *   unparseable body), or it resolved for an event that is no longer configured (a fetch landing
     *   after a switch or leave must not resurrect the departed membership). **Nothing is persisted and
     *   nothing is torn down.**
     * - [RefreshOutcome.ABSENT] — the event is definitively gone **and** this membership's own persisted
     *   deadline has passed. Only then may the caller tear the membership down (capability
     *   `manage-membership`).
     *
     * On [RefreshOutcome.ABSENT] this performs the teardown itself and then returns the verdict; callers
     * need do nothing with the result but may read it (tests do).
     *
     * The two witnesses of ABSENT are independent and one of them is **offline**, so no backend fault can
     * manufacture both — see [confirmedGone] for why that matters and why the test is exact rather than
     * heuristic. A `NotFound` whose deadline has not passed is deliberately INCONCLUSIVE: the backend is
     * disbelieved, not obeyed.
     */
    suspend fun refresh(eventId: String, fetched: JoinLoad): RefreshOutcome {
        val current = configSource.config.value ?: return RefreshOutcome.INCONCLUSIVE
        // A result that arrives after a switch or a leave describes someone else's membership.
        if (current.eventId != eventId) return RefreshOutcome.INCONCLUSIVE
        return when (fetched) {
            // A refresh reads the details without a link, so it never asks whether a key opens the event.
            JoinLoad.Failed, JoinLoad.WrongLink -> RefreshOutcome.INCONCLUSIVE
            JoinLoad.NotFound ->
                // Witness two: this membership's OWN deadline. Absent it — or before it — the backend is
                // disbelieved. Both readings mean "I could not tell", never "destroy it".
                if (configSource.isPastDeletion(current.deletesAt)) {
                    leaveEvent.leave()
                    RefreshOutcome.ABSENT
                } else {
                    RefreshOutcome.INCONCLUSIVE
                }
            // The backend's POSITIVE word that the event finished and its photos are gone (capability
            // `manage-membership`): unlike a bare "not found" it cannot be manufactured by a missing row or a
            // misconfigured zone, so it needs no second witness — the membership ends at once, from any wake.
            is JoinLoad.Found -> if (fetched.completion.completed) {
                leaveEvent.leave()
                RefreshOutcome.COMPLETED
            } else {
                var next = current
                // Name CONVERGENCE on the served name — not a fill for a membership that lacks one:
                // every membership carries a name (capability `join-event`, no decode default), so this
                // arm exists so a diverged persisted name can still be repaired toward the backend's
                // value. It is the only path by which that could ever happen. An unchanged name saves
                // nothing.
                if (current.name != fetched.name) next = next.copy(name = fetched.name)
                // The completion state (capability `event-lifetime`). Closing is final, so a stale answer can never
                // reopen a membership's closed event; the counts are the waiting line's and simply follow the server.
                if (fetched.completion.closed && !current.closed) next = next.copy(closed = true)
                fetched.completion.members?.let { if (it != current.members) next = next.copy(members = it) }
                if (next != current) configSource.save(next)
                if (next.closed) RefreshOutcome.CLOSED else RefreshOutcome.REFRESHED
            }
        }
    }
}

/**
 * The sealed answer of [MembershipRefresh.refresh] — what a fetched details result meant for the
 * persisted membership. Only [ABSENT] is destructive, and reaching it requires two independent
 * witnesses, one of them offline.
 */
enum class RefreshOutcome {
    /** Resolved and folded in (name refresh and/or backfill). Nothing further is due. */
    REFRESHED,

    /** Resolved and folded in, and the membership's event has closed — now or by an earlier answer. Nothing was torn down. */
    CLOSED,

    /** Could not tell, or no longer ours. Nothing is persisted and nothing is torn down. */
    INCONCLUSIVE,

    /** Definitively gone AND past this membership's own deadline — the membership WAS torn down. */
    ABSENT,

    /** The backend said the event COMPLETED (its photos deleted) — the membership WAS torn down. */
    COMPLETED,
}
