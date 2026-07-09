package app.snapsync.presentation

import app.snapsync.permission.PermissionStatus

/**
 * Display-ready projection of config presence, permission, and the latest sync snapshot. Once an
 * event is configured the screen is always the **joined layer** (name · QR · share · leave); permission
 * and sync activity are just moods of the one-line status ([SyncHealth]). No counts are carried — the
 * screen answers "is it healthy?", not "how many of N".
 */
sealed interface UiState {
    /**
     * The create-event landing layer (event-creation-ui), shown while no event is connected
     * (`config == null`) and no create is in flight. Carries an optional pre-formatted inline
     * [error] — the last create failure's copy (sticky until the next attempt) or a transient
     * invalid-deeplink message. Config-absent outranks everything, so this is the top reduction rung.
     */
    data class CreateEvent(val error: String? = null) : UiState

    /**
     * A `POST /events` create request is in flight (`config == null`, creation status `InFlight`): a
     * preparing spinner with no input. Auto-resolves — success provisions config (off this layer),
     * failure returns to [CreateEvent] with an inline error.
     */
    data object CreatingEvent : UiState

    /**
     * An interactive join confirmation is in progress for [eventId] (capability `join-event`), shown
     * as a full-screen "Join event" surface. Only entered when no event is configured (a first join);
     * a join while already configured is a switch, carried as [Joined.pendingSwitch] instead. [phase]
     * drives the surface (loading details → ready/blocked/retry → committing → commit-failed).
     */
    data class JoiningEvent(val eventId: String, val phase: JoinPhase) : UiState

    /**
     * An event is connected (`config != null`) — the joined layer. Always renders the invite (name,
     * QR, share) and leave, regardless of permission; [health] is the one-line status mood.
     * [pendingSwitch] overlays a leave-style switch confirmation when a deeplink for a **different**
     * event was scanned while joined (capability `join-event`).
     */
    data class Joined(val health: SyncHealth, val pendingSwitch: PendingSwitch? = null) : UiState
}

/**
 * A leave-style switch confirmation over the joined screen: a deeplink for a **different** [eventId]
 * was scanned while already joined. [phase] mirrors a first join (details load then commit); on
 * confirm the container runs leave-then-join.
 */
data class PendingSwitch(val eventId: String, val phase: JoinPhase)

/**
 * The phase of a join/switch confirmation surface (capability `join-event`). The details fetch gates
 * the confirm; the commit (enroll → provision) follows on confirm.
 */
sealed interface JoinPhase {
    /** Fetching `GET /event/:id` details ("Loading event details…"). */
    data object Loading : JoinPhase

    /**
     * Details loaded; the confirm (Join/Switch) is offered. [name] is the event name (required,
     * non-null — a details response without a name is a transient failure, not a loaded phase).
     * [defaultCutoff] seeds the capture-date cutoff row's default (the event's fetched `createdAt`,
     * already a UTC `…Z` string; `null` when the marker carried none) — capability `photo-date-cutoff`.
     */
    data class Ready(val name: String, val defaultCutoff: String?) : JoinPhase

    /** The event does not exist (404) — an invalid/expired invite; no confirm offered. */
    data object NotFound : JoinPhase

    /** The details fetch failed transiently (network/5xx); a Retry re-runs it. */
    data object LoadFailed : JoinPhase

    /** The confirm was taken; enroll + provision are in flight. [name] carries the loaded name. */
    data class Committing(val name: String) : JoinPhase

    /** Enrollment/commit failed (or a switch's join failed after leaving); a Retry re-runs the join. */
    data class CommitFailed(val name: String) : JoinPhase
}

/**
 * The joined-layer one-line health, the sole thing the status line renders. There is no standalone
 * "not syncing" state — the only reason contribution cannot run is missing permission ([NeedsAccess]),
 * the sole attention state (spec: sync-status-screen).
 */
sealed interface SyncHealth {
    /**
     * Permission is not `GRANTED` while an event is connected. [permission] is `NOT_DETERMINED`
     * (never asked → tapping the status line requests it) or `DENIED` (tapping opens Settings). The
     * only health that carries a background. Sharing the invite still works with no access.
     */
    data class NeedsAccess(val permission: PermissionStatus) : SyncHealth

    /** Joined, permission granted, but persisted state has not been read yet — a neutral first frame. */
    data object Loading : SyncHealth

    /** Everything shared and received — the settled state (no arrows). */
    data object InSync : SyncHealth

    /**
     * Work remaining in at least one direction. Each arrow is shown by completeness and pulses by live
     * activity (spec: sync-status-screen): [upload] from `synced < total` (shown) × `pending > 0` (pulse),
     * [download] from `downloaded < total` (shown) × `inFlight > 0` (pulse).
     */
    data class Syncing(val upload: Arrow, val download: Arrow) : SyncHealth
}

/** A direction arrow's render state: absent, shown-idle, or shown-and-animating. */
enum class Arrow { HIDDEN, STATIC, PULSING }
