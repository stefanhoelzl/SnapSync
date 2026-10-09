package app.snapsync.ui

import app.snapsync.ui.components.RangeChoiceActions
import app.snapsync.ui.components.RangeGestures
import app.snapsync.ui.state.UiIntent

/**
 * The status screen's callback bundle — **the one tap → [UiIntent] table**.
 *
 * Every screen that renders the status screen builds its bundle here: the iOS UI adapter and the
 * desktop panes. Each tap becomes the one [UiIntent] naming it, handed to [dispatch] — the `Ui` port's `onIntent`
 * handler on a device, the container's own `onIntent` in a harness. What an intent MEANS is the container's table,
 * never this one: this binds taps to names, and nothing else. It is clicked (`HostStatusActionsTest`), and the table
 * clicked is the table that ships.
 */
fun statusActions(dispatch: (UiIntent) -> Unit): StatusActions = rangeGestures(dispatch).let { range ->
    statusActions(dispatch, range)
}

/** The range calendars' gestures, each one intent — the create form's and the participation surfaces' alike. */
private fun rangeGestures(dispatch: (UiIntent) -> Unit) = RangeGestures(
    onDay = { dispatch(UiIntent.RangeDay(it)) },
    onDragStart = { dispatch(UiIntent.RangeDragStart(it)) },
    onDragEnd = { dispatch(UiIntent.RangeDragEnd(it)) },
    onSweep = { anchor, day -> dispatch(UiIntent.RangeSweep(anchor, day)) },
    onFromHour = { dispatch(UiIntent.RangeFromHour(it)) },
    onFromMinute = { dispatch(UiIntent.RangeFromMinute(it)) },
    onUntilHour = { dispatch(UiIntent.RangeUntilHour(it)) },
    onUntilMinute = { dispatch(UiIntent.RangeUntilMinute(it)) },
    onUntilMinuteDrag = { dispatch(UiIntent.RangeUntilMinuteDrag) },
)

private fun statusActions(dispatch: (UiIntent) -> Unit, range: RangeGestures) = StatusActions(
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
    create = CreateActions(
        onName = { dispatch(UiIntent.CreateName(it)) },
        range = range,
        onCreate = { dispatch(UiIntent.ConfirmCreate) },
    ),
    // The store page is the build's, opened by the container; a state that names
    // no store makes the intent inert.
    onOpenStore = { dispatch(UiIntent.OpenAppStore) },
    participation = ParticipationActions(
        choices = RangeChoiceActions(
            onPreset = { dispatch(UiIntent.RangePreset(it)) },
            onOpen = { dispatch(UiIntent.RangePickerOpen) },
            gestures = range,
            onConfirm = { dispatch(UiIntent.RangePickerConfirm) },
            onDismiss = { dispatch(UiIntent.RangePickerDismiss) },
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
