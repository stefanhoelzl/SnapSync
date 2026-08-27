package app.snapsync.ui

import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.Direction
import kotlinx.datetime.LocalDateTime

/**
 * Everything the status screen can ASK FOR, grouped by the surface that asks.
 *
 * These are the SCREEN's vocabulary and deliberately not the domain's. `model/UserCommands` exists and
 * was considered: it holds eleven commands where this holds eighteen, and the shapes do not correspond.
 * `UserCommands.commitJoin` takes nine arguments including `eventId` and `name`, while [onConfirmJoin]
 * takes four — the container supplies the event's identity from state. [onCancelJoin], [onRetryLoad],
 * [onRenameStatusConsumed] and [onConfirmSwitch] have no domain counterpart at all; they are screen-local
 * flow control. Passing `UserCommands` in would move that adaptation out of the container and into the
 * screen, which would then have to know event ids and domain argument order.
 *
 * NESTED, NOT FLAT. This held all eighteen callbacks as one list, which is where bundling stops helping:
 * a bundle of a bundle has as many fields as it has GROUPS, and the groups here are the surfaces — the
 * join gate, the joined layer, the access prompts, the switch confirmation. Seven fields for eighteen
 * callbacks, and a reader now finds a callback by asking which surface owns it.
 *
 * EVERY FIELD IS DEFAULTED, at both levels, exactly as the eighteen parameters were. That is what lets
 * each host wire only what it needs: the forge leaves join and reconfigure inert while the world harness
 * binds them to a real graph. A group that defaults to a default-constructed group keeps that property —
 * a host that wires no join actions writes nothing about them, as before.
 *
 * [onSendDiagnostics] stays NULLABLE rather than defaulting to an inert lambda, and that is a contract
 * rather than a convenience: a build with no reporting channel must wire no gesture at all, because an
 * affordance that silently does nothing is the one outcome `diagnostic-logging` forbids.
 */
class StatusActions(
    val join: JoinGateActions = JoinGateActions(),
    val joined: JoinedActions = JoinedActions(),
    val access: AccessActions = AccessActions(),
    val switch: SwitchActions = SwitchActions(),
    // Create an event (capability `event-creation-ui`): the trimmed name and the chosen `[startsAt, endsAt]`
    // event window as LOCAL wall-clock values (the container converts each to a canonical `…Z` string).
    // Top-level rather than in a group of one: the create layer asks for exactly this and nothing else.
    val onCreateEvent: (String, LocalDateTime, LocalDateTime) -> Unit = { _, _, _ -> },
    // The shareable-count preview (capability `join-share-count`): given the chosen range, how many of the
    // member's own gallery photos would be shared. `null` = no count available (DENIED / unresolved grant)
    // → the row is omitted. Permission-aware and cheap (no per-asset resource read) — the permission branch
    // and the LIMITED snapshot live inside the compose-built query. Top-level because it is a QUERY, not an
    // action, and because both the join gate and the reconfigure surface ask it.
    val shareableCount: suspend (cutoff: CaptureCutoff, until: CaptureCeiling?) -> Int? = { _, _ -> null },
    // The hidden diagnostic dump (capability `diagnostic-logging`): fired by a double-tap on the app-name
    // label, carrying what the operator wrote about the problem and the surface they wrote it from.
    //
    // NULLABLE rather than defaulting to an inert lambda, and that is a contract rather than a
    // convenience: a build with no reporting channel must wire no gesture at all, because an affordance
    // that silently does nothing is the one outcome `diagnostic-logging` forbids.
    val onSendDiagnostics: ((note: String, screen: String) -> Unit)? = null,
)

/**
 * What the JOIN GATE asks for (capability `join-event`), routed to the container intents.
 *
 * The confirm and retry carry the chosen capture-date range (`cutoff` = lower bound, `until` = upper;
 * capability `photo-selection-policy`, both always present), the chosen direction, and the album opt-in
 * (capability `event-album`). [onRetryJoin] is distinct from [onConfirmJoin] because a retry commits
 * WITHOUT passing back through the loaded phase.
 */
class JoinGateActions(
    val onConfirmJoin: (CaptureCutoff, CaptureCeiling, Direction, Boolean) -> Unit = { _, _, _, _ -> },
    val onRetryJoin: (CaptureCutoff, CaptureCeiling, Direction, Boolean) -> Unit = { _, _, _, _ -> },
    // The photo-access explainer's confirm: requests permission, then advances to the confirm surface.
    // The only route from the join gate to the system dialog.
    val onAcknowledgeAccess: () -> Unit = {},
    val onCancelJoin: () -> Unit = {},
    val onRetryLoad: () -> Unit = {},
)

/** What the JOINED layer asks for: the action cluster, and the two halves of a rename. */
class JoinedActions(
    val onLeaveEvent: () -> Unit = {},
    val onShareInvite: () -> Unit = {},
    // Commit an in-place reconfigure (capability `reconfigure-membership`): the event the surface was
    // opened for, the new direction, the chosen capture-date range (`minPhotoDate` floor-clamped and
    // `maxPhotoDate` ceiling-clamped on the far side in `ReconfigureEvent`), and the album opt-in.
    val onReconfigure: (String, Direction, CaptureCutoff, CaptureCeiling, Boolean) -> Unit =
        { _, _, _, _, _ -> },
    // Rename the joined event (capability `event-rename`): the event the dialog was opened for and the
    // new name. Fired by the pen beside the heading; the outcome arrives back via `renameStatus`.
    val onRenameEvent: (String, String) -> Unit = { _, _ -> },
    // Clear the rename latch once the screen has acted on a terminal status, so a second rename starts
    // from a clean sequence rather than re-reading the previous outcome.
    val onRenameStatusConsumed: () -> Unit = {},
)

/**
 * The three ways a screen asks about PHOTO ACCESS — grouped because they are one escalation, not three
 * unrelated taps: request the grant, send the member to Settings when the grant can no longer be asked
 * for, and widen a partial one (capability `limited-photo-access`).
 */
class AccessActions(
    val onRequestPermission: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    // The joined layer's "Choose more photos" tap under a partial grant: presents the system
    // limited-library picker.
    val onChoosePhotos: () -> Unit = {},
)

/**
 * The switch confirmation (scanning a different event while joined). [onConfirmSwitch] runs the leave and
 * nothing else, so it carries no choices — the join surface the leave reveals owns every one of them.
 */
class SwitchActions(
    val onConfirmSwitch: () -> Unit = {},
    val onCancelSwitch: () -> Unit = {},
)
