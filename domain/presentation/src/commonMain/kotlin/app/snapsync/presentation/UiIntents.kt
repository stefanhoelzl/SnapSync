package app.snapsync.presentation

import app.snapsync.model.UiIntent

/**
 * What a person did on the screen, handed over by the platform's UI (the `Ui` port's `onIntent` handler) — **the one
 * intent → method table** of the status container. Every case is one public intent of [StatusContainerHost], so a UI
 * binds nothing itself: it names the tap, and this decides what it means. Exhaustive, so a case added to [UiIntent]
 * without a meaning here does not compile. An extension rather than a member: the container's own function count is
 * a measured ceiling, and this table adds no behaviour of its own.
 */
@Suppress("CyclomaticComplexMethod") // one arm per case of a closed set: a table, not a decision tree
fun StatusContainerHost.onIntent(intent: UiIntent) {
    when (intent) {
        is UiIntent.CreateEvent -> onCreateEvent(intent.name, intent.startsAt, intent.endsAt)
        UiIntent.RequestPermission -> access.onRequestPermission()
        UiIntent.ChoosePhotos -> access.onChoosePhotos()
        UiIntent.OpenSettings -> access.onOpenSettings()
        UiIntent.LeaveEvent -> onLeaveEvent()
        UiIntent.ShareInvite -> onShareInvite()
        UiIntent.OpenAppStore -> onOpenAppStore()
        UiIntent.ConfirmLeaveOpen -> surfaces.onConfirmLeaveOpen()
        UiIntent.ConfirmLeaveDismiss -> surfaces.onConfirmLeaveDismiss()
        UiIntent.RenameOpen -> surfaces.onRenameOpen()
        UiIntent.RenameDismiss -> surfaces.onRenameDismiss()
        UiIntent.ReportBugOpen -> surfaces.onReportBugOpen()
        UiIntent.ReportBugDismiss -> surfaces.onReportBugDismiss()
        UiIntent.OpenReconfigure -> surfaces.onOpenReconfigure()
        UiIntent.CancelReconfigure -> surfaces.onCancelReconfigure()
        is UiIntent.RenameEvent -> onRenameEvent(intent.eventId, intent.name)
        UiIntent.RenameStatusConsumed -> onRenameStatusConsumed()
        UiIntent.Reconfigure -> onReconfigure()
        is UiIntent.ShareOn -> form.onShareOn(intent.on)
        is UiIntent.ReceiveOn -> form.onReceiveOn(intent.on)
        is UiIntent.SaveToAlbum -> form.onSaveToAlbum(intent.on)
        is UiIntent.FromPreset -> form.onFromPreset(intent.preset)
        is UiIntent.FromCustom -> form.onFromCustom(intent.value)
        is UiIntent.UntilPreset -> form.onUntilPreset(intent.preset)
        is UiIntent.UntilCustom -> form.onUntilCustom(intent.value)
        UiIntent.RetryLoad -> onRetryLoad()
        UiIntent.ConfirmJoin -> onConfirmJoin()
        UiIntent.ConfirmSwitch -> onConfirmSwitch()
        UiIntent.RetryJoin -> onRetryJoin()
        UiIntent.AcknowledgeAccess -> onAcknowledgeAccess()
        UiIntent.CancelJoin -> onCancelJoin()
        UiIntent.CancelSwitch -> onCancelSwitch()
        is UiIntent.SendDiagnostics -> onSendDiagnostics(intent.note, intent.screen)
    }
}
