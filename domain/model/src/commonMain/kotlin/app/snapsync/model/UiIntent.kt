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
    data object ReportBugOpen : UiIntent
    data object ReportBugDismiss : UiIntent
    data object OpenReconfigure : UiIntent
    data object CancelReconfigure : UiIntent
    data class RenameEvent(val eventId: String, val name: String) : UiIntent
    data object RenameStatusConsumed : UiIntent
    data object Reconfigure : UiIntent
    data class ShareOn(val on: Boolean) : UiIntent
    data class ReceiveOn(val on: Boolean) : UiIntent
    data class SaveToAlbum(val on: Boolean) : UiIntent
    data class FromPreset(val preset: FromChoice) : UiIntent
    data class FromCustom(val value: LocalDateTime) : UiIntent
    data class UntilPreset(val preset: UntilChoice) : UiIntent
    data class UntilCustom(val value: LocalDateTime) : UiIntent
    data object RetryLoad : UiIntent
    data object ConfirmJoin : UiIntent
    data object ConfirmSwitch : UiIntent
    data object RetryJoin : UiIntent
    data object AcknowledgeAccess : UiIntent
    data object CancelJoin : UiIntent
    data object CancelSwitch : UiIntent

    /** The diagnostic dump, with the operator's [note] and the opaque label of the [screen] it was sent from. */
    data class SendDiagnostics(val note: String, val screen: String) : UiIntent
}
