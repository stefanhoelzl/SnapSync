package app.snapsync.ui

import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.Direction
import kotlinx.datetime.LocalDateTime

/**
 * Everything the status screen can ASK FOR, as one bundle.
 *
 * These are the SCREEN's vocabulary and deliberately not the domain's. `model/UserCommands` exists and
 * was considered: it holds eleven commands where this holds eighteen, and the shapes do not correspond.
 * `UserCommands.commitJoin` takes nine arguments including `eventId` and `name`, while [onConfirmJoin]
 * takes four — the container supplies the event's identity from state. [onCancelJoin], [onRetryLoad],
 * [onRenameStatusConsumed] and [onConfirmSwitch] have no domain counterpart at all; they are screen-local
 * flow control. Passing `UserCommands` in would move that adaptation out of the container and into the
 * screen, which would then have to know event ids and domain argument order.
 *
 * EVERY FIELD IS DEFAULTED, exactly as the eighteen parameters this replaces were. That is what lets each
 * host wire only what it needs: the forge leaves join and reconfigure inert while the world harness binds
 * them to a real graph, and the three production call sites each pass around ten of the eighteen. A
 * required bundle would force every host to construct all eighteen, which is the property that made the
 * loose parameters worth keeping until now.
 *
 * [onSendDiagnostics] stays NULLABLE rather than defaulting to an inert lambda, and that is a contract
 * rather than a convenience: a build with no reporting channel must wire no gesture at all, because an
 * affordance that silently does nothing is the one outcome `diagnostic-logging` forbids.
 */
class StatusActions(
    val onRequestPermission: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onLeaveEvent: () -> Unit = {},
    val onShareInvite: () -> Unit = {},
    // Commit an in-place reconfigure (capability `reconfigure-membership`): the event the surface was
    // opened for, the new direction, the chosen capture-date range (`minPhotoDate` floor-clamped and
    // `maxPhotoDate` — nullable, absent = no ceiling — ceiling-clamped on the far side in `ReconfigureEvent`),
    // and the album opt-in.
    val onReconfigure: (String, Direction, CaptureCutoff, CaptureCeiling, Boolean) -> Unit = { _, _, _, _, _ -> },
    // Create an event (capability `event-creation-ui`): the trimmed name and the chosen `[startsAt, endsAt]`
    // event window as LOCAL wall-clock values (the container converts each to a canonical `…Z` string).
    val onCreateEvent: (String, LocalDateTime, LocalDateTime) -> Unit = { _, _, _ -> },
    // Join-gate actions (capability `join-event`), routed to the container intents. The confirm/retry
    // actions carry the chosen capture-date range (`cutoff` = the lower bound, `until` = the upper bound;
    // capability `photo-selection-policy`, both always present), the chosen participation direction
    // (capability `join-event`), and the album opt-in (`saveToAlbum`, capability `event-album`).
    val onConfirmJoin: (CaptureCutoff, CaptureCeiling, Direction, Boolean) -> Unit = { _, _, _, _ -> },
    // The photo-access explainer's confirm: requests permission, then advances to the confirm surface.
    // The only route from the join gate to the system dialog (capability `join-event`).
    val onAcknowledgeAccess: () -> Unit = {},
    // The joined layer's "Choose more photos" tap under a partial grant (capability
    // `limited-photo-access`): presents the system limited-library picker.
    val onChoosePhotos: () -> Unit = {},
    val onCancelJoin: () -> Unit = {},
    val onRetryLoad: () -> Unit = {},
    val onRetryJoin: (CaptureCutoff, CaptureCeiling, Direction, Boolean) -> Unit = { _, _, _, _ -> },
    // The switch confirmation's confirm: it runs the leave and nothing else, so it carries no choices —
    // the join surface the leave reveals owns every one of them (capability `join-event`).
    val onConfirmSwitch: () -> Unit = {},
    val onCancelSwitch: () -> Unit = {},
    // The join-time shareable-count preview (capability `join-share-count`): given the chosen cutoff, how
    // many of the member's own gallery photos would be shared. `null` = no count available (DENIED /
    // unresolved grant) → the row is omitted. Permission-aware and cheap (no per-asset resource read) — the
    // permission-branch and the LIMITED snapshot live inside the compose-built query. Default `{ null }`
    // keeps the row absent wherever it is not wired (forge, plain tests).
    val shareableCount: suspend (cutoff: CaptureCutoff, until: CaptureCeiling?) -> Int? = { _, _ -> null },
    // The hidden diagnostic dump (capability `diagnostic-logging`): fired by a double-tap on the
    // app-name label, carrying what the operator wrote about the problem and the surface they wrote it
    // from. `null` — the default, and every build with no reporting channel — wires no gesture and can
    // open no sheet, so a build that can send nothing offers nothing that suggests it can.
    val onSendDiagnostics: ((note: String, screen: String) -> Unit)? = null,
    // Rename the joined event (capability `event-rename`): the event the dialog was opened for and the
    // new name. Fired by the pen beside the heading; the outcome arrives back via [renameStatus].
    val onRenameEvent: (String, String) -> Unit = { _, _ -> },
    // Clear the rename latch once this screen has acted on a terminal status, so a second rename starts
    // from a clean sequence rather than re-reading the previous outcome.
    val onRenameStatusConsumed: () -> Unit = {},
)
