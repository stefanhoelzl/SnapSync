package app.snapsync.ui

import app.snapsync.model.UiIntent
import app.snapsync.ui.components.RangeChoiceActions

/**
 * The status screen's callback bundle — **the one tap → [UiIntent] table** (spec `sync-status`, "The screen's
 * callback bundle is built in one place").
 *
 * Every screen that renders the status screen builds its bundle here: the iOS UI adapter, the forge binary, the
 * desktop pane. Each tap becomes the one [UiIntent] naming it, handed to [dispatch] — the `Ui` port's `onIntent`
 * handler on a device, the container's own `onIntent` in a harness. What an intent MEANS is the container's table,
 * never this one: this binds taps to names, and nothing else. It is clicked (`HostStatusActionsTest`), and the table
 * clicked is the table that ships.
 */
fun statusActions(dispatch: (UiIntent) -> Unit): StatusActions = StatusActions(
    join = JoinGateActions(
        onConfirmJoin = { dispatch(UiIntent.ConfirmJoin) },
        onAcknowledgeAccess = { dispatch(UiIntent.AcknowledgeAccess) },
        onCancelJoin = { dispatch(UiIntent.CancelJoin) },
        onRetryLoad = { dispatch(UiIntent.RetryLoad) },
        onRetryJoin = { dispatch(UiIntent.RetryJoin) },
    ),
    joined = JoinedActions(
        onLeaveEvent = { dispatch(UiIntent.LeaveEvent) },
        onShareInvite = { dispatch(UiIntent.ShareInvite) },
        onReconfigure = { dispatch(UiIntent.Reconfigure) },
        // The heading rename (capability `manage-membership`): the command, and the latch reset the screen fires once
        // it has acted on a terminal value.
        onRenameEvent = { eventId, name -> dispatch(UiIntent.RenameEvent(eventId, name)) },
        onRenameStatusConsumed = { dispatch(UiIntent.RenameStatusConsumed) },
    ),
    access = AccessActions(
        onRequestPermission = { dispatch(UiIntent.RequestPermission) },
        onOpenSettings = { dispatch(UiIntent.OpenSettings) },
        onChoosePhotos = { dispatch(UiIntent.ChoosePhotos) },
    ),
    surfaces = SurfaceActions(
        onConfirmLeaveOpen = { dispatch(UiIntent.ConfirmLeaveOpen) },
        onConfirmLeaveDismiss = { dispatch(UiIntent.ConfirmLeaveDismiss) },
        onRenameOpen = { dispatch(UiIntent.RenameOpen) },
        onRenameDismiss = { dispatch(UiIntent.RenameDismiss) },
        onOpenReconfigure = { dispatch(UiIntent.OpenReconfigure) },
        onCancelReconfigure = { dispatch(UiIntent.CancelReconfigure) },
        onReportBugOpen = { dispatch(UiIntent.ReportBugOpen) },
        onReportBugDismiss = { dispatch(UiIntent.ReportBugDismiss) },
    ),
    switch = SwitchActions(
        onConfirmSwitch = { dispatch(UiIntent.ConfirmSwitch) },
        onCancelSwitch = { dispatch(UiIntent.CancelSwitch) },
    ),
    onCreateEvent = { name, startsAt, endsAt -> dispatch(UiIntent.CreateEvent(name, startsAt, endsAt)) },
    // The store button's URL is read from state by the container (capability `app-update-required`), so the argument
    // the screen passes is not needed; a state holding no store URL makes the intent inert.
    onOpenLink = { dispatch(UiIntent.OpenAppStore) },
    participation = ParticipationActions(
        choices = RangeChoiceActions(
            onFromPreset = { dispatch(UiIntent.FromPreset(it)) },
            onFromCustom = { dispatch(UiIntent.FromCustom(it)) },
            onUntilPreset = { dispatch(UiIntent.UntilPreset(it)) },
            onUntilCustom = { dispatch(UiIntent.UntilCustom(it)) },
        ),
        onShareOn = { dispatch(UiIntent.ShareOn(it)) },
        onReceiveOn = { dispatch(UiIntent.ReceiveOn(it)) },
        onSaveToAlbum = { dispatch(UiIntent.SaveToAlbum(it)) },
    ),
    onSendDiagnostics = { note, screen -> dispatch(UiIntent.SendDiagnostics(note, screen)) },
)
