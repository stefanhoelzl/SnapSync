package app.snapsync.feature.membership

import app.snapsync.model.CaptureDate
import app.snapsync.model.JoinLoad
import app.snapsync.model.confirmedGone
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore

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
    private val configSource: ConfigSource,
    private val store: ConfigStore,
    /** Canonical `…Z` "now" — the OFFLINE witness of the absence verdict. Injected as a `model`-typed
     *  lambda over the composition's one clock, matching `HeadlessCreate`'s seam. */
    private val now: () -> CaptureDate,
    /**
     * The ordinary local teardown (capability `leave-event`), performed on a confirmed absence.
     *
     * A SIBLING of this rule inside `feature/membership`, so referencing it directly is not a
     * feature-blindness breach — and the consequence belongs with the decision. It lives here rather than
     * as a `when` in the flow because the flow transcriber's closed grammar admits no `when` inside an
     * escaping `scope.launch` (specs `architecture-diagrams` / `module-architecture`), and its own remedy
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
     *   the **window + retention backfill** (capability `event-rejoin-reconciliation`) filling the
     *   event's `endsAt` and `deletesAt` — each only when ABSENT. Doing them in one save is what stops
     *   the rewrites from losing each other's field. The membership's own `maxPhotoDate` is **not**
     *   backfilled: it is required on every persisted membership (capability `join-event`), so a config
     *   that decoded at all already carries one.
     * - [RefreshOutcome.INCONCLUSIVE] — the fetch could not tell (offline, transport, non-404 status,
     *   unparseable body), or it resolved for an event that is no longer configured (a fetch landing
     *   after a switch or leave must not resurrect the departed membership). **Nothing is persisted and
     *   nothing is torn down.**
     * - [RefreshOutcome.ABSENT] — the event is definitively gone **and** this membership's own persisted
     *   deadline has passed. Only then may the caller tear the membership down (capability
     *   `leave-event`).
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
            JoinLoad.Failed -> RefreshOutcome.INCONCLUSIVE
            JoinLoad.NotFound ->
                // Witness two: this membership's OWN deadline. Absent it — or before it — the backend is
                // disbelieved. Both readings mean "I could not tell", never "destroy it".
                if (confirmedGone(current.deletesAt, now())) {
                    leaveEvent.leave()
                    RefreshOutcome.ABSENT
                } else {
                    RefreshOutcome.INCONCLUSIVE
                }
            is JoinLoad.Found -> {
                var next = current
                // Name CONVERGENCE on the served name — not a fill for a membership that lacks one:
                // every membership carries a name (capability `event-link`, no decode default), so this
                // arm exists so a diverged persisted name can still be repaired toward the backend's
                // value. It is the only path by which that could ever happen. An unchanged name saves
                // nothing.
                if (current.name != fetched.name) next = next.copy(name = fetched.name)
                // Event-window backfill (capability `event-rejoin-reconciliation`): a membership
                // persisted before the event window existed carries a `null` `endsAt`; fill it from the
                // freshly fetched details, so a legacy member gains the event's declared end.
                //
                // The membership's own **ceiling** is NOT backfilled here any more: it is required on
                // every persisted membership (capability `join-event`), so a config that decoded at all
                // already has one and there is nothing absent to fill. A config lacking it does not reach
                // this rule — it failed to decode and read as no config.
                if (current.endsAt == null) next = next.copy(endsAt = fetched.endsAt)
                // Retention backfill: until this lands the membership's deadline reads as "never
                // reached" and the self-leave cannot fire — the safe direction, mirroring the unbounded
                // ceiling above.
                if (current.deletesAt == null) next = next.copy(deletesAt = fetched.deletesAt)
                if (next != current) store.save(next)
                RefreshOutcome.REFRESHED
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

    /** Could not tell, or no longer ours. Nothing is persisted and nothing is torn down. */
    INCONCLUSIVE,

    /** Definitively gone AND past this membership's own deadline — the membership WAS torn down. */
    ABSENT,
}
