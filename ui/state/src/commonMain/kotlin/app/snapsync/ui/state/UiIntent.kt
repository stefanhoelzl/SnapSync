package app.snapsync.ui.state

import kotlinx.datetime.LocalDate

/**
 * What a person did on the status screen, as the one value that crosses from a platform's UI to the core
 * (`docs/architecture.md`, "Events arrive through `listen`": the `Ui` port's `onIntent` handler).
 *
 * One case per public intent of the status container — the screen names the tap, the container decides what it
 * means. It replaced the tap table each host used to bind to the container's methods by hand: a platform UI now
 * hands over values, so an Android screen emits the same cases and binds nothing.
 */
sealed interface UiIntent {
    /** Create an event from the create form as it stands — the Create button. */
    data object ConfirmCreate : UiIntent

    /** The create form's name, as typed so far. */
    data class CreateName(val name: String) : UiIntent

    /** The back-dated sheet's choice: create the event, guests joining for [days] more days. */
    data class KeepOpenFor(val days: Int) : UiIntent

    /** The back-dated sheet closed unchosen — its Cancel, a swipe or the scrim: nothing is created. */
    data object KeepOpenDismiss : UiIntent

    /** A tap on [day] in the range calendar. */
    data class RangeDay(val day: LocalDate) : UiIntent

    /** The range's start dragged to [day]. */
    data class RangeDragStart(val day: LocalDate) : UiIntent

    /** The range's last day dragged to [day]. */
    data class RangeDragEnd(val day: LocalDate) : UiIntent

    /** A long press on [anchor], swept to [day]: a new range over the days between them. */
    data class RangeSweep(val anchor: LocalDate, val day: LocalDate) : UiIntent

    /** A start or an end time wheel settled on [value]. */
    data class RangeFromHour(val value: Int) : UiIntent
    data class RangeFromMinute(val value: Int) : UiIntent
    data class RangeUntilHour(val value: Int) : UiIntent
    data class RangeUntilMinute(val value: Int) : UiIntent

    /** The end's minute wheel started moving while its hour is blank. */
    data object RangeUntilMinuteDrag : UiIntent

    /** Open the range calendar over a participation surface; take what it shows; or close it unchanged. */
    data object RangePickerOpen : UiIntent
    data object RangePickerConfirm : UiIntent
    data object RangePickerDismiss : UiIntent
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

    /** The invite's QR code: shown on request, and dismissed. */
    data object QrOpen : UiIntent
    data object QrDismiss : UiIntent
    data object ReportBugOpen : UiIntent

    /**
     * "Report this" beside a refusal the user can only tell us about: the same sheet, opened with a description already
     * written for [message].
     */
    data class ReportRefusal(val message: ScreenMessage) : UiIntent
    data object ReportBugDismiss : UiIntent

    /** The app menu: open it, close it, and its "Report a problem" row. */
    data object MenuOpen : UiIntent
    data object MenuDismiss : UiIntent
    data object MenuReportBug : UiIntent

    /** The app menu's mobile-data switch: the device's choice, applied as it is flipped. */
    data class MobileData(val on: Boolean) : UiIntent

    /** One of the app menu's links, opened outside the app. */
    data class OpenLink(val link: AppLink) : UiIntent

    /** The brief word on a sent report, tapped away before it cleared itself. */
    data object ReportNoticeDismiss : UiIntent
    data object OpenReconfigure : UiIntent
    data object CancelReconfigure : UiIntent
    data class RenameEvent(val eventId: String, val name: String) : UiIntent
    data object RenameStatusConsumed : UiIntent

    /** The settings' "Stop sharing these photos?": apply the held change, or drop it. */
    data object ConfirmStopSharing : UiIntent
    data object KeepSharing : UiIntent
    data class ShareOn(val on: Boolean) : UiIntent
    data class ReceiveOn(val on: Boolean) : UiIntent
    data class SaveToAlbum(val on: Boolean) : UiIntent
    data class RangePreset(val preset: RangeChoice) : UiIntent
    data object RetryLoad : UiIntent
    data object ConfirmJoin : UiIntent
    data object ConfirmSwitch : UiIntent
    data object RetryJoin : UiIntent
    data object CancelJoin : UiIntent
    data object CancelSwitch : UiIntent

    /** The diagnostic dump, with the operator's [note] and the opaque label of the [screen] it was sent from. */
    data class SendDiagnostics(val note: String, val screen: String) : UiIntent
}
