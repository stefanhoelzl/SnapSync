package app.snapsync.feature.membership

import app.snapsync.domain.event.EventId
import app.snapsync.model.EventConfig
import app.snapsync.model.JoinLoad
import app.snapsync.model.confirmedGone
import app.snapsync.services.backend.EventDirectory
import app.snapsync.services.config.ConfigService

/**
 * The membership-refresh **rule**: fold a freshly fetched event-details result
 * into the persisted membership, and say what happened.
 *
 * One job, named for the need it serves rather than for any single field it touches — reconcile the
 * persisted membership against fresh details. (It was `EventName` when the name was all it refreshed;
 * the window backfill, the retention backfill, and the absence verdict are the same operation seen from
 * three sides, so the name moved with the job.)
 *
 * The *fetch* is made for **one** trigger — the foreground's work, which keeps the membership current
 * ([refreshJoined]) — over the `EventDirectory` service; this feature owns the decision of what the
 * fetched value means. (`Provision` once fetched too, to fill a name a scan couldn't fetch while
 * offline. A membership can no longer arrive nameless, and every provision route has just loaded or
 * minted its details, so that fetch was redundant by construction and is gone.) [RefreshOutcome] is what the
 * rule ANSWERS, not what a trigger branches on: the one consequence — marking a deleted event — is performed here (see
 * [stopTransfers]), so no trigger can reach it by a different route.
 *
 * Seated in `feature/membership` because the membership config is this feature's durable state
 * (one-writer: join/provision saves it, leave clears it, and this refresh rewrites it whole).
 */
class MembershipRefresh(
    private val configSource: ConfigService,
    /**
     * What a confirmed deletion stops — every upload and download for the event, as a leave stops them — while the
     * membership itself stays — the app never leaves an event on its own. The effect is
     * built in `compose/`; the decision is here, so every trigger reaches the same consequence by construction: one
     * verdict cannot mean two things depending on which trigger observed it.
     */
    private val stopTransfers: suspend () -> Unit,
) {

    /**
     * Keep the joined membership [eventId] current: fetch its details from [directory], then [refresh] — nothing while
     * not joined.
     */
    suspend fun refreshJoined(eventId: EventId?, directory: EventDirectory) {
        eventId?.let { refresh(it, directory.fetch(it).toJoinLoad()) }
    }

    /**
     * Fold a freshly [fetched] details result into the persisted membership and return what it means.
     *
     * - [RefreshOutcome.REFRESHED] — the fetch resolved for the still-configured event. Two rewrites ride
     *   together in **one** whole-config save: **name convergence** (an unchanged name saves nothing), and
     *   the **window + retention backfill** filling the
     *   event's `endsAt` and `deletesAt` — each only when ABSENT. Doing them in one save is what stops
     *   the rewrites from losing each other's field. The membership's own `maxPhotoDate` is **not**
     *   backfilled: it is required on every persisted membership, so a config
     *   that decoded at all already carries one.
     * - [RefreshOutcome.INCONCLUSIVE] — the fetch could not tell (offline, transport, non-404 status,
     *   unparseable body), or it resolved for an event that is no longer configured (a fetch landing
     *   after a switch or leave must not resurrect the departed membership). **Nothing is persisted and
     *   nothing is torn down.**
     * - [RefreshOutcome.ABSENT] — the event is definitively gone **and** this membership's own persisted
     *   deadline has passed. Only then is the membership marked deleted.
     * - [RefreshOutcome.COMPLETED] — the backend said the event's photos are deleted; the membership is marked so.
     *
     * Marking deleted is done here ([markDeleted]) and the verdict returned; callers need do nothing with the result
     * but may read it (tests do). The membership is never torn down: it stays, shown as over, until the member leaves.
     *
     * The two witnesses of ABSENT are independent and one of them is **offline**, so no backend fault can
     * manufacture both — see [confirmedGone] for why that matters and why the test is exact rather than
     * heuristic. A `NotFound` whose deadline has not passed is deliberately INCONCLUSIVE: the backend is
     * disbelieved, not obeyed.
     */
    suspend fun refresh(eventId: EventId, fetched: JoinLoad): RefreshOutcome {
        val current = configSource.config.value ?: return RefreshOutcome.INCONCLUSIVE
        // A result that arrives after a switch or a leave describes someone else's membership.
        if (current.eventId != eventId) return RefreshOutcome.INCONCLUSIVE
        return when (fetched) {
            // A refresh reads the details without a link, so it never asks whether a key opens the event.
            is JoinLoad.Failed, JoinLoad.WrongLink -> RefreshOutcome.INCONCLUSIVE
            JoinLoad.NotFound ->
                // Witness two: this membership's OWN deadline. Absent it — or before it — the backend is
                // disbelieved. Both readings mean "I could not tell", never "destroy it".
                if (configSource.isPastDeletion(current.deletesAt)) {
                    markDeleted(current)
                    RefreshOutcome.ABSENT
                } else {
                    RefreshOutcome.INCONCLUSIVE
                }
            // The backend's POSITIVE word that the event finished and its photos are gone: unlike a bare
            // "not found" it cannot be manufactured by a missing row or a
            // misconfigured zone, so it needs no second witness — the membership is marked at once, from any wake.
            is JoinLoad.Found -> if (fetched.completion.completed) {
                markDeleted(current)
                RefreshOutcome.COMPLETED
            } else {
                converge(current, fetched)
            }
        }
    }

    /**
     * The service answered a write for [eventId] — a rename, the shared set — that the event's photos were deleted: its
     * positive word, as a completed event read is, so the membership is marked at once and stays, shown as over: the
     * app never leaves an event on its own.
     */
    suspend fun confirmedDeleted(eventId: EventId): RefreshOutcome {
        val current = configSource.config.value ?: return RefreshOutcome.INCONCLUSIVE
        if (current.eventId != eventId) return RefreshOutcome.INCONCLUSIVE
        markDeleted(current)
        return RefreshOutcome.COMPLETED
    }

    /** Fold a found, not-completed event's details into [current] — saved only when something changed. */
    private suspend fun converge(current: EventConfig, fetched: JoinLoad.Found): RefreshOutcome {
        var next = current
        // Name CONVERGENCE on the served name — not a fill for a membership that lacks one:
        // every membership carries a name (no decode default), so this
        // arm exists so a diverged persisted name can still be repaired toward the backend's
        // value. It is the only path by which that could ever happen. An unchanged name saves
        // nothing.
        if (current.name != fetched.name) next = next.copy(name = fetched.name)
        // The counts and the open-until date simply follow the server; one it did not serve keeps the last known.
        fetched.completion.members?.let { if (it != current.members) next = next.copy(members = it) }
        fetched.openUntil?.let { if (it != current.openUntil) next = next.copy(openUntil = it) }
        if (next != current) configSource.save(next)
        return RefreshOutcome.REFRESHED
    }

    /**
     * Mark [current] as an event whose photos are deleted, once: the membership stays and every transfer for it stops.
     * Deletion is final, so an already-marked membership is left as it is.
     */
    private suspend fun markDeleted(current: EventConfig) {
        if (current.deleted) return
        configSource.save(current.copy(deleted = true))
        stopTransfers()
    }
}

/**
 * The sealed answer of [MembershipRefresh.refresh] — what a fetched details result meant for the
 * persisted membership. Reaching [ABSENT] requires two independent witnesses, one of them offline.
 */
enum class RefreshOutcome {
    /** Resolved and folded in (name refresh and/or backfill). Nothing further is due. */
    REFRESHED,

    /** Could not tell, or no longer ours. Nothing is persisted and nothing is torn down. */
    INCONCLUSIVE,

    /** Definitively gone AND past this membership's own deadline — the membership is marked deleted. */
    ABSENT,

    /** The backend said the event COMPLETED (its photos deleted) — the membership is marked deleted. */
    COMPLETED,
}
