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
     * An event is connected (`config != null`) — the joined layer. Always renders the invite (name,
     * QR, share) and leave, regardless of permission; [health] is the one-line status mood.
     */
    data class Joined(val health: SyncHealth) : UiState
}

/**
 * The joined-layer one-line health, the sole thing the status line renders. There is no standalone
 * "not syncing" state — the only reason contribution cannot run is missing permission ([NeedsAccess]),
 * the sole attention state (design.md D2).
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
     * activity (design.md D3/D4): [upload] from `synced < total` (shown) × `pending > 0` (pulse),
     * [download] from `downloaded < total` (shown) × `inFlight > 0` (pulse).
     */
    data class Syncing(val upload: Arrow, val download: Arrow) : SyncHealth
}

/** A direction arrow's render state: absent, shown-idle, or shown-and-animating. */
enum class Arrow { HIDDEN, STATIC, PULSING }
