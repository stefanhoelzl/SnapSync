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
     * The photo-access explainer (capability `join-event`): the consent surface shown **before** the
     * system permission dialog is ever raised. Entered from the details fetch instead of [Ready], on a
     * **first** join only (`config == null` — a switch never explains) and only while permission is
     * `NOT_DETERMINED` (the sole state from which iOS can still raise the dialog; from `DENIED` a request
     * is a silent no-op, so an explainer promising a dialog would be false).
     *
     * Its confirm requests permission and advances to [Ready]; its cancel discards the pending join like
     * any other phase. [name] and [startsAt] are carried **solely to hand off to [Ready]** — this
     * phase renders neither. Permission is a snapshot taken when the phase is chosen, not an observation:
     * the phase advances only by user action.
     */
    data class ExplainAccess(val name: String, val startsAt: String) : JoinPhase

    /**
     * Details loaded; the confirm (Join/Switch) is offered. [name] is the event name (required,
     * non-null — a details response without a name is a transient failure, not a loaded phase).
     *
     * [startsAt] is the event's **start date** — required, non-null, already a canonical UTC `…Z` string
     * (`HttpEventDetailsSource` normalizes it and fails the load rather than invent one). It is both the
     * cutoff row's **default** and its **floor** (capability `photo-selection-policy`): the row cannot be
     * empty, and the confirm cannot join below it, so joining at whole-library scope is unrepresentable.
     *
     * It also decides the cutoff selector's shape: when [startsAt] is in the **future**, the "Now" preset
     * would clamp to this same instant, so it is offered **disabled** rather than as a button that
     * visibly does nothing.
     */
    data class Ready(val name: String, val startsAt: String) : JoinPhase

    /** The event does not exist (404) — an invalid/expired invite; no confirm offered. */
    data object NotFound : JoinPhase

    /** The details fetch failed transiently (network/5xx); a Retry re-runs it. */
    data object LoadFailed : JoinPhase

    /** The confirm was taken; enroll + provision are in flight. [name] carries the loaded name. */
    data class Committing(val name: String, val startsAt: String) : JoinPhase

    /**
     * Enrollment/commit failed (or a switch's join failed after leaving); a Retry re-runs the join.
     *
     * [startsAt] rides along for the same reason [name] does: a Retry commits **without** passing back
     * through the loaded phase, so the floor has to still be here or the retry would join unclamped.
     */
    data class CommitFailed(val name: String, val startsAt: String) : JoinPhase
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

    /**
     * The event has not begun: the membership's `startsAt` is still in the future (capability
     * `sync-status-screen`). Carries the start instant so the screen can say *when* — a bare "not started
     * yet" invites exactly the question it fails to answer.
     *
     * It ranks **below** [NeedsAccess] and **above** the snapshot-derived values. Permission outranks it
     * because permission is the only **actionable** state, and a member must resolve it *before* the event
     * begins or they will miss the start; burying it behind a clock line would ambush them with a
     * permission prompt at the very moment the party starts. Everything below is outranked because, before
     * the start, nothing of the member's **can** be syncing — the cutoff floor guarantees it (capability
     * `photo-selection-policy`), so a snapshot-derived line would say nothing true this does not say better.
     *
     * Unlike every other health, this one depends on **wall-clock time** rather than the ledger, so no
     * snapshot emission retires it — `StatusContainerHost` runs a foreground tick for that.
     */
    data class NotStarted(val startsAt: String) : SyncHealth

    /**
     * Uploads are blocked: this device holds no valid attestation token, and the attempt to obtain one
     * **failed** (capability `device-attestation`).
     *
     * **A user should essentially never see this**, and that is by construction rather than by hope. The
     * app renews at every wake — and *opening the app is a wake*, so the very act of looking at this screen
     * triggers a renewal that clears it. It therefore only survives long enough to be rendered when the
     * renewal itself fails: the device is offline, or the backend is refusing us. Both are real, persistent
     * problems that no amount of waiting fixes, and both would otherwise be invisible — the uploads would
     * simply `401` forever behind a screen that cheerfully said "Syncing".
     *
     * It is deliberately NOT raised merely because a token is stale. A stale token that renews on the next
     * wake is a non-event, and flashing an error at the user for it would be noise.
     *
     * It ranks below [NotStarted] for the same reason it ranks below [NeedsAccess]: before the event
     * begins, nothing of this member's **can** be uploading, so an unusable token is not yet their problem
     * — and two attention lines at once would only compete.
     */
    data object Unattested : SyncHealth

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
