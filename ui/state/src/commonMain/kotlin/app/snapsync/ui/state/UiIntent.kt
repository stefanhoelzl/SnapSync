package app.snapsync.ui.state

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a person did on the status screen, as the one value that crosses from a platform's UI to the core
 * (`docs/architecture.md`, "Events arrive through `listen`": the `Ui` port's `onIntent` handler).
 *
 * One case per public intent of the status container — the screen names the tap, the container decides what it
 * means. It replaced the tap table each host used to bind to the container's methods by hand: a platform UI now
 * hands over values, so an Android screen emits the same cases and binds nothing.
 */
@Serializable
sealed interface UiIntent {
    /** Create an event from the create form as it stands — the Create button. */
    @Serializable
    @SerialName("ConfirmCreate")
    data object ConfirmCreate : UiIntent

    /** The create form's name, as typed so far. */
    @Serializable
    @SerialName("CreateName")
    data class CreateName(val name: String) : UiIntent

    /** The back-dated sheet's choice: create the event, guests joining for [days] more days. */
    @Serializable
    @SerialName("KeepOpenFor")
    data class KeepOpenFor(val days: Int) : UiIntent

    /** The back-dated sheet closed unchosen — its Cancel, a swipe or the scrim: nothing is created. */
    @Serializable
    @SerialName("KeepOpenDismiss")
    data object KeepOpenDismiss : UiIntent

    /** A tap on [day] in the range calendar. */
    @Serializable
    @SerialName("RangeDay")
    data class RangeDay(val day: LocalDate) : UiIntent

    /** The range's start dragged to [day]. */
    @Serializable
    @SerialName("RangeDragStart")
    data class RangeDragStart(val day: LocalDate) : UiIntent

    /** The range's last day dragged to [day]. */
    @Serializable
    @SerialName("RangeDragEnd")
    data class RangeDragEnd(val day: LocalDate) : UiIntent

    /** A long press on [anchor], swept to [day]: a new range over the days between them. */
    @Serializable
    @SerialName("RangeSweep")
    data class RangeSweep(val anchor: LocalDate, val day: LocalDate) : UiIntent

    /** A start or an end time wheel settled on [value]. */
    @Serializable
    @SerialName("RangeFromHour")
    data class RangeFromHour(val value: Int) : UiIntent

    @Serializable
    @SerialName("RangeFromMinute")
    data class RangeFromMinute(val value: Int) : UiIntent

    @Serializable
    @SerialName("RangeUntilHour")
    data class RangeUntilHour(val value: Int) : UiIntent

    @Serializable
    @SerialName("RangeUntilMinute")
    data class RangeUntilMinute(val value: Int) : UiIntent

    /** The end's minute wheel started moving while its hour is blank. */
    @Serializable
    @SerialName("RangeUntilMinuteDrag")
    data object RangeUntilMinuteDrag : UiIntent

    /** Open the range calendar over a participation surface; take what it shows; or close it unchanged. */
    @Serializable
    @SerialName("RangePickerOpen")
    data object RangePickerOpen : UiIntent

    @Serializable
    @SerialName("RangePickerConfirm")
    data object RangePickerConfirm : UiIntent

    @Serializable
    @SerialName("RangePickerDismiss")
    data object RangePickerDismiss : UiIntent

    @Serializable
    @SerialName("RequestPermission")
    data object RequestPermission : UiIntent

    @Serializable
    @SerialName("ChoosePhotos")
    data object ChoosePhotos : UiIntent

    @Serializable
    @SerialName("OpenSettings")
    data object OpenSettings : UiIntent

    @Serializable
    @SerialName("LeaveEvent")
    data object LeaveEvent : UiIntent

    @Serializable
    @SerialName("ShareInvite")
    data object ShareInvite : UiIntent

    @Serializable
    @SerialName("OpenAppStore")
    data object OpenAppStore : UiIntent

    @Serializable
    @SerialName("ConfirmLeaveOpen")
    data object ConfirmLeaveOpen : UiIntent

    @Serializable
    @SerialName("ConfirmLeaveDismiss")
    data object ConfirmLeaveDismiss : UiIntent

    @Serializable
    @SerialName("RenameOpen")
    data object RenameOpen : UiIntent

    @Serializable
    @SerialName("RenameDismiss")
    data object RenameDismiss : UiIntent

    /** The invite's QR code: shown on request, and dismissed. */
    @Serializable
    @SerialName("QrOpen")
    data object QrOpen : UiIntent

    @Serializable
    @SerialName("QrDismiss")
    data object QrDismiss : UiIntent

    @Serializable
    @SerialName("ReportBugOpen")
    data object ReportBugOpen : UiIntent

    /**
     * "Report this" beside a refusal the user can only tell us about: the same sheet, opened with a description already
     * written for [message].
     */
    @Serializable
    @SerialName("ReportRefusal")
    data class ReportRefusal(val message: ScreenMessage) : UiIntent

    @Serializable
    @SerialName("ReportBugDismiss")
    data object ReportBugDismiss : UiIntent

    /** The app menu: open it, close it, and its "Report a problem" row. */
    @Serializable
    @SerialName("MenuOpen")
    data object MenuOpen : UiIntent

    @Serializable
    @SerialName("MenuDismiss")
    data object MenuDismiss : UiIntent

    @Serializable
    @SerialName("MenuReportBug")
    data object MenuReportBug : UiIntent

    /** The app menu's mobile-data switch: the device's choice, applied as it is flipped. */
    @Serializable
    @SerialName("MobileData")
    data class MobileData(val on: Boolean) : UiIntent

    /** One of the app menu's links, opened outside the app. */
    @Serializable
    @SerialName("OpenLink")
    data class OpenLink(val link: AppLink) : UiIntent

    /** The brief word on a sent report, tapped away before it cleared itself. */
    @Serializable
    @SerialName("ReportNoticeDismiss")
    data object ReportNoticeDismiss : UiIntent

    @Serializable
    @SerialName("OpenReconfigure")
    data object OpenReconfigure : UiIntent

    @Serializable
    @SerialName("CancelReconfigure")
    data object CancelReconfigure : UiIntent

    @Serializable
    @SerialName("RenameEvent")
    data class RenameEvent(val eventId: String, val name: String) : UiIntent

    @Serializable
    @SerialName("RenameStatusConsumed")
    data object RenameStatusConsumed : UiIntent

    /** The settings' "Stop sharing these photos?": apply the held change, or drop it. */
    @Serializable
    @SerialName("ConfirmStopSharing")
    data object ConfirmStopSharing : UiIntent

    @Serializable
    @SerialName("KeepSharing")
    data object KeepSharing : UiIntent

    @Serializable
    @SerialName("ShareOn")
    data class ShareOn(val on: Boolean) : UiIntent

    @Serializable
    @SerialName("ReceiveOn")
    data class ReceiveOn(val on: Boolean) : UiIntent

    @Serializable
    @SerialName("SaveToAlbum")
    data class SaveToAlbum(val on: Boolean) : UiIntent

    @Serializable
    @SerialName("RangePreset")
    data class RangePreset(val preset: RangeChoice) : UiIntent

    @Serializable
    @SerialName("RetryLoad")
    data object RetryLoad : UiIntent

    @Serializable
    @SerialName("ConfirmJoin")
    data object ConfirmJoin : UiIntent

    @Serializable
    @SerialName("ConfirmSwitch")
    data object ConfirmSwitch : UiIntent

    @Serializable
    @SerialName("RetryJoin")
    data object RetryJoin : UiIntent

    @Serializable
    @SerialName("CancelJoin")
    data object CancelJoin : UiIntent

    @Serializable
    @SerialName("CancelSwitch")
    data object CancelSwitch : UiIntent

    /** The diagnostic dump, with the operator's [note] and the opaque label of the [screen] it was sent from. */
    @Serializable
    @SerialName("SendDiagnostics")
    data class SendDiagnostics(val note: String, val screen: String) : UiIntent
}
