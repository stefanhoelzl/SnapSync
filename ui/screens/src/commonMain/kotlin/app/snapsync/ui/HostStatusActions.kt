package app.snapsync.ui

import app.snapsync.presentation.StatusContainerHost
import app.snapsync.ui.components.RangeChoiceActions

/**
 * The status screen's callback bundle, bound to a container — **the one tap → intent table** (spec
 * `sync-status-screen`, "The screen's callback bundle is built in one place").
 *
 * Every host that composes the screen calls this: the iOS app, the forge binary, the desktop pane. The table used to
 * be written out at each of them, and three hand copies of an eighteen-callback table were already disagreeing — only
 * the iOS app bound the update-required store button, the others left it on [StatusActions]' inert default. A copy
 * is where a crossed or forgotten binding hides, because nothing that runs in a test composes a shell. Here it is
 * clicked (`HostStatusActionsTest`), and the table clicked is the table that ships.
 *
 * Everything is the container's, so it is bound here and nowhere else — a callback this function does not bind is
 * a change to its signature, visible at every call site. (The shareable count is no longer a callback at all: the
 * container computes it over its lane-decorated query bundle and the screen renders it.)
 */
fun statusActions(host: StatusContainerHost): StatusActions = StatusActions(
    join = JoinGateActions(
        onConfirmJoin = host::onConfirmJoin,
        onAcknowledgeAccess = host::onAcknowledgeAccess,
        onCancelJoin = host::onCancelJoin,
        onRetryLoad = host::onRetryLoad,
        onRetryJoin = host::onRetryJoin,
    ),
    joined = JoinedActions(
        onLeaveEvent = host::onLeaveEvent,
        onShareInvite = host::onShareInvite,
        onReconfigure = host::onReconfigure,
        // The heading rename (capability `event-rename`): the command, and the latch reset the screen fires once
        // it has acted on a terminal value.
        onRenameEvent = host::onRenameEvent,
        onRenameStatusConsumed = host::onRenameStatusConsumed,
    ),
    access = AccessActions(
        onRequestPermission = host.access::onRequestPermission,
        onOpenSettings = host.access::onOpenSettings,
        onChoosePhotos = host.access::onChoosePhotos,
    ),
    surfaces = SurfaceActions(
        onConfirmLeaveOpen = host.surfaces::onConfirmLeaveOpen,
        onConfirmLeaveDismiss = host.surfaces::onConfirmLeaveDismiss,
        onRenameOpen = host.surfaces::onRenameOpen,
        onRenameDismiss = host.surfaces::onRenameDismiss,
        onOpenReconfigure = host.surfaces::onOpenReconfigure,
        onCancelReconfigure = host.surfaces::onCancelReconfigure,
        onReportBugOpen = host.surfaces::onReportBugOpen,
        onReportBugDismiss = host.surfaces::onReportBugDismiss,
    ),
    switch = SwitchActions(
        onConfirmSwitch = host::onConfirmSwitch,
        onCancelSwitch = host::onCancelSwitch,
    ),
    onCreateEvent = host::onCreateEvent,
    // The store button's URL is read from state by the container (capability `min-app-version`), so the argument
    // the screen passes is not needed; a state holding no store URL makes this inert.
    onOpenLink = { host.onOpenAppStore() },
    participation = ParticipationActions(
        choices = RangeChoiceActions(
            onFromPreset = host.form::onFromPreset,
            onFromCustom = host.form::onFromCustom,
            onUntilPreset = host.form::onUntilPreset,
            onUntilCustom = host.form::onUntilCustom,
        ),
        onShareOn = host.form::onShareOn,
        onReceiveOn = host.form::onReceiveOn,
        onSaveToAlbum = host.form::onSaveToAlbum,
    ),
    // Null when the build has no reporting channel: the screen then wires no gesture (capability `diagnostic-logging`).
    onSendDiagnostics = host.onSendDiagnostics,
)
