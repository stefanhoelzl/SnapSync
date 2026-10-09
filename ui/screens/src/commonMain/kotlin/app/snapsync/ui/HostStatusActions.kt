package app.snapsync.ui

import app.snapsync.model.UiIntent
import app.snapsync.ui.components.RangeChoiceActions

/**
 * The status screen's callback bundle — **the one tap → [UiIntent] table**.
 *
 * Every screen that renders the status screen builds its bundle here: the iOS UI adapter and the
 * desktop panes. Each tap becomes the one [UiIntent] naming it, handed to [dispatch] — the `Ui` port's `onIntent`
 * handler on a device, the container's own `onIntent` in a harness. What an intent MEANS is the container's table,
 * never this one: this binds taps to names, and nothing else. It is clicked (`HostStatusActionsTest`), and the table
 * clicked is the table that ships.
 */
fun statusActions(dispatch: (UiIntent) -> Unit): StatusActions = StatusActions(
    join = JoinGateActions(
        onConfirmJoin = { dispatch(UiIntent.ConfirmJoin) },
        onCancelJoin = { dispatch(UiIntent.CancelJoin) },
        onRetryLoad = { dispatch(UiIntent.RetryLoad) },
        onRetryJoin = { dispatch(UiIntent.RetryJoin) },
    ),
    joined = JoinedActions(
        onLeaveEvent = { dispatch(UiIntent.LeaveEvent) },
        onShareInvite = { dispatch(UiIntent.ShareInvite) },
        onQrOpen = { dispatch(UiIntent.QrOpen) },
        onQrDismiss = { dispatch(UiIntent.QrDismiss) },
        withdrawal = WithdrawalActions(
            onStopSharing = { dispatch(UiIntent.ConfirmStopSharing) },
            onKeepSharing = { dispatch(UiIntent.KeepSharing) },
        ),
        // The heading rename: the command, and the latch reset the screen fires once
        // it has acted on a terminal value.
        onRenameEvent = { eventId, name -> dispatch(UiIntent.RenameEvent(eventId, name)) },
        onRenameStatusConsumed = { dispatch(UiIntent.RenameStatusConsumed) },
    ),
    access = AccessActions(
        onRequestPermission = { dispatch(UiIntent.RequestPermission) },
        onOpenSettings = { dispatch(UiIntent.OpenSettings) },
        onChoosePhotos = { dispatch(UiIntent.ChoosePhotos) },
    ),
    surfaces = surfaceActions(dispatch),
    switch = SwitchActions(
        onConfirmSwitch = { dispatch(UiIntent.ConfirmSwitch) },
        onCancelSwitch = { dispatch(UiIntent.CancelSwitch) },
    ),
    onCreateEvent = { name, startsAt, endsAt -> dispatch(UiIntent.CreateEvent(name, startsAt, endsAt)) },
    // The store button's URL is read from state by the container, so the argument the screen
    // passes is not needed; a state holding no store URL makes the intent inert.
    onOpenLink = { dispatch(UiIntent.OpenAppStore) },
    participation = ParticipationActions(
        choices = RangeChoiceActions(
            onPreset = { dispatch(UiIntent.RangePreset(it)) },
            onCustom = { from, until -> dispatch(UiIntent.RangeCustom(from, until)) },
        ),
        onShareOn = { dispatch(UiIntent.ShareOn(it)) },
        onReceiveOn = { dispatch(UiIntent.ReceiveOn(it)) },
        onSaveToAlbum = { dispatch(UiIntent.SaveToAlbum(it)) },
    ),
    menu = MenuActions(
        onSendDiagnostics = { note, screen -> dispatch(UiIntent.SendDiagnostics(note, screen)) },
        onMenuOpen = { dispatch(UiIntent.MenuOpen) },
        onMenuDismiss = { dispatch(UiIntent.MenuDismiss) },
        onReportBug = { dispatch(UiIntent.MenuReportBug) },
        onOpenLink = { dispatch(UiIntent.OpenLink(it)) },
        onReportNoticeDismiss = { dispatch(UiIntent.ReportNoticeDismiss) },
        onMobileData = { dispatch(UiIntent.MobileData(it)) },
    ),
)

/** The overlays' opening and dismissal taps, each one intent. */
private fun surfaceActions(dispatch: (UiIntent) -> Unit) = SurfaceActions(
    onConfirmLeaveOpen = { dispatch(UiIntent.ConfirmLeaveOpen) },
    onConfirmLeaveDismiss = { dispatch(UiIntent.ConfirmLeaveDismiss) },
    onRenameOpen = { dispatch(UiIntent.RenameOpen) },
    onRenameDismiss = { dispatch(UiIntent.RenameDismiss) },
    onOpenReconfigure = { dispatch(UiIntent.OpenReconfigure) },
    onCancelReconfigure = { dispatch(UiIntent.CancelReconfigure) },
    onReportBugOpen = { dispatch(UiIntent.ReportBugOpen) },
    onReportBugDismiss = { dispatch(UiIntent.ReportBugDismiss) },
    onReportRefusal = { dispatch(UiIntent.ReportRefusal(it)) },
)
