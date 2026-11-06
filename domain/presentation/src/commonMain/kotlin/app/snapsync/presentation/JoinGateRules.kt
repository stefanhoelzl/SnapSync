package app.snapsync.presentation

import app.snapsync.domain.event.Event
import app.snapsync.domain.event.EventId
import app.snapsync.domain.membership.InviteFact
import app.snapsync.domain.membership.JoinCommit
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.query.membership.JoinDetails
import app.snapsync.ui.state.JoinPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The service is being updated: it says when asking again is worth it, so the details load again by themselves then —
 * Retry stays offered meanwhile (decision record `changes/tell-service-faults`, D3). Bounded ([maintenanceWait]), and
 * only while [pending] still shows that failed load: [reload] runs once, or never. `null` for any other load.
 */
internal fun CoroutineScope.reloadWhileUpdating(
    loaded: Loaded,
    pending: StateFlow<PendingJoin?>,
    reload: () -> Unit,
): Job? {
    val failed = loaded.failed?.takeIf { it.fault == ServiceFault.UPDATING } ?: return null
    val shown = pending.value
    return launch {
        delay(maintenanceWait(failed.retryAfterSeconds))
        if (pending.value === shown) reload()
    }
}

/**
 * What an invite's details load came to: the phase it shows, the event it served, what it says of joining, and — when
 * it got no answer — how the service failed it.
 */
internal class Loaded(
    val phase: JoinProgress,
    val event: Event?,
    val invite: InviteFact,
    val failed: JoinDetails.Unanswered? = null,
)

/** [loaded] shown on [eventId]'s pending join — only while it is still the active pending target. */
internal fun PendingJoin?.showing(eventId: EventId, loaded: Loaded): PendingJoin? = when (this?.eventId == eventId) {
    true -> this.copy(phase = loaded.phase, loaded = loaded.event, service = loaded.failed?.fault)
    false -> this
}

/**
 * What [commit] came to for [p] — only while it is still the pending join. A join that is not answered `Committed`
 * may still have LANDED: one cut short after its membership was written is answered `Failed`, and what follows the
 * write is follow-up the next foreground repeats. So the membership [joined] decides:
 *  · it names this event → the join landed; drop the pending join, and the joined screen is the truth. A retry
 *    would find it joined anyway.
 *  · otherwise → the failure's own phase: `CommitFailed` pins the Retry that re-runs it, and a final refusal (an
 *    event over, a link that does not open it) its own phase with no Retry.
 */
internal fun PendingJoin?.settled(
    p: PendingJoin,
    detailed: JoinProgress.Detailed,
    commit: JoinCommit,
    joined: Membership?,
    refusal: DeviceRefusal?,
    attestFault: ServiceFault?,
): PendingJoin? = when {
    this?.eventId != p.eventId -> this
    commit == JoinCommit.Committed || joined?.let { it.event.id } == p.eventId -> null
    else -> p.copy(
        phase = failedPhase(commit, detailed.event, refusal),
        service = failedService(commit, refusal, attestFault),
    )
}

/**
 * The phase a commit that did not land shows: a closed event is a wall no retry moves, so it
 * offers none; anything else may heal and keeps the Retry.
 */
internal fun failedPhase(
    commit: JoinCommit,
    event: EventDetails,
    refusal: DeviceRefusal?,
): JoinProgress = when (commit) {
    JoinCommit.EventOver -> JoinProgress.Closed
    JoinCommit.WrongLink -> JoinProgress.WrongLink
    // Refused for this phone's credential: told why when the service said why — a refused phone
    // is told why it cannot join. Without a verdict — the attempt to verify got no answer — it is the ordinary failure.
    JoinCommit.Unverified if refusal != null ->
        JoinProgress.Detailed(event, JoinPhase.Detailed.Step.DeviceRefused, refusal.message)
    else -> JoinProgress.Detailed(event, JoinPhase.Detailed.Step.CommitFailed)
}

/**
 * How SnapSync's service failed a commit that did not land: the join's own answer, or — for a join refused for want
 * of a proof, with no refusal to tell — how the service failed to give one. `null` when the service was not the cause.
 */
internal fun failedService(commit: JoinCommit, refusal: DeviceRefusal?, attestFault: ServiceFault?): ServiceFault? =
    when (commit) {
        is JoinCommit.Failed -> commit.fault
        JoinCommit.Unverified if refusal == null -> attestFault
        else -> null
    }

/**
 * How long a details load waits before asking a service that is being updated again: what it suggested, bounded so a
 * missing, malformed or huge hint can neither spin nor stall the screen (decision record `changes/tell-service-faults`,
 * D3).
 */
internal fun maintenanceWait(retryAfterSeconds: Long?): Duration =
    (retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS).coerceIn(
        MIN_RETRY_AFTER_SECONDS,
        MAX_RETRY_AFTER_SECONDS,
    ).seconds

internal const val DEFAULT_RETRY_AFTER_SECONDS = 30L
internal const val MIN_RETRY_AFTER_SECONDS = 5L
internal const val MAX_RETRY_AFTER_SECONDS = 300L

/** The steps a confirm may be taken from: the first, and each failure a Retry may heal. */
internal val RETRYABLE_STEPS =
    setOf(JoinPhase.Detailed.Step.Ready, JoinPhase.Detailed.Step.CommitFailed, JoinPhase.Detailed.Step.DeviceRefused)

/** [this] event as the join screen shows it. */
internal fun Event.details(): EventDetails =
    EventDetails(name.text, range.start, range.end, deletesAt, members?.active, openUntil)

/** What a details read came to, as the gate shows it. */
internal fun loadedOf(load: JoinDetails): Loaded = when (load) {
    is JoinDetails.Found -> found(load)
    JoinDetails.NotFound -> Loaded(JoinProgress.NotFound, null, InviteFact.NotJoinable)
    is JoinDetails.Unanswered -> Loaded(JoinProgress.LoadFailed, null, InviteFact.Unknown, failed = load)
    JoinDetails.WrongLink -> Loaded(JoinProgress.WrongLink, null, InviteFact.NotJoinable)
}

/** An event whose photos are deleted is refused before any choice is offered. */
private fun found(load: JoinDetails.Found): Loaded = if (load.completed) {
    Loaded(JoinProgress.Closed, load.event, InviteFact.NotJoinable)
} else {
    Loaded(
        JoinProgress.Detailed(load.event.details(), JoinPhase.Detailed.Step.Ready),
        load.event,
        InviteFact.Joinable,
    )
}
