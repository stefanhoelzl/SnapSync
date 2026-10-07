package app.snapsync.model

import kotlinx.datetime.LocalDateTime

/**
 * What a person did on the status screen, as the one value that crosses from a platform's UI to the core
 * (`docs/architecture.md`, "Events arrive through `listen`": the `Ui` port's `onIntent` handler).
 *
 * One case per public intent of the status container — the screen names the tap, the container decides what it
 * means. It replaced the tap table each host used to bind to the container's methods by hand: a platform UI now
 * hands over values, so an Android screen emits the same cases and binds nothing.
 */
sealed interface UiIntent {
    data class CreateEvent(val name: String, val startsAt: LocalDateTime, val endsAt: LocalDateTime) : UiIntent
    data object RequestPermission : UiIntent
    data object ChoosePhotos : UiIntent
    data object OpenSettings : UiIntent
    data object LeaveEvent : UiIntent
    data object ShareInvite : UiIntent
    data object OpenAppStore : UiIntent
    data object ConfirmLeaveOpen : UiIntent
    data object ConfirmLeaveDismiss : UiIntent
    data object RenameOpen : UiIntent
    data object RenameDismiss : UiIntent

    /** The invite's QR code (capability `manage-membership`): shown on request, and dismissed. */
    data object QrOpen : UiIntent
    data object QrDismiss : UiIntent
    data object ReportBugOpen : UiIntent
    data object ReportBugDismiss : UiIntent

    /** The app menu (capability `sync-status`): open it, close it, and its "Report a problem" row. */
    data object MenuOpen : UiIntent
    data object MenuDismiss : UiIntent
    data object MenuReportBug : UiIntent

    /** The app menu's mobile-data switch (capability `mobile-data`): the device's choice, applied as it is flipped. */
    data class MobileData(val on: Boolean) : UiIntent

    /** One of the app menu's links, opened outside the app. */
    data class OpenLink(val link: AppLink) : UiIntent

    /** The brief word on a sent report, tapped away before it cleared itself. */
    data object ReportNoticeDismiss : UiIntent
    data object OpenReconfigure : UiIntent
    data object CancelReconfigure : UiIntent
    data class RenameEvent(val eventId: String, val name: String) : UiIntent
    data object RenameStatusConsumed : UiIntent

    /** The settings' "Stop sharing these photos?" (capability `manage-membership`): apply the held change, or drop it. */
    data object ConfirmStopSharing : UiIntent
    data object KeepSharing : UiIntent
    data class ShareOn(val on: Boolean) : UiIntent
    data class ReceiveOn(val on: Boolean) : UiIntent
    data class SaveToAlbum(val on: Boolean) : UiIntent
    data class RangePreset(val preset: RangeChoice) : UiIntent

    /** A custom range; a `null` bound keeps the one already picked (or the window's, if none was). */
    data class RangeCustom(val from: LocalDateTime?, val until: LocalDateTime?) : UiIntent
    data object RetryLoad : UiIntent
    data object ConfirmJoin : UiIntent
    data object ConfirmSwitch : UiIntent
    data object RetryJoin : UiIntent
    data object CancelJoin : UiIntent
    data object CancelSwitch : UiIntent

    /** The diagnostic dump, with the operator's [note] and the opaque label of the [screen] it was sent from. */
    data class SendDiagnostics(val note: String, val screen: String) : UiIntent
}
