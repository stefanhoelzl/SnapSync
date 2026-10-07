package app.snapsync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.model.UiState
import app.snapsync.ui.components.RangeChoiceActions
import kotlinx.datetime.LocalDateTime

/*
 * Test-only builders for the status screen's action bundles, carrying the inert defaults the production
 * bundles no longer have (law "Function-typed parameters have no defaults in production"). A screen test
 * states only the actions it is about; everything else does nothing.
 */

internal fun testActions(
    join: JoinGateActions = testJoinGateActions(),
    joined: JoinedActions = testJoinedActions(),
    access: AccessActions = testAccessActions(),
    switch: SwitchActions = testSwitchActions(),
    surfaces: SurfaceActions = testSurfaceActions(),
    onCreateEvent: (String, LocalDateTime, LocalDateTime) -> Unit = { _, _, _ -> },
    onOpenLink: (String) -> Unit = {},
    participation: ParticipationActions = testParticipationActions(),
    onSendDiagnostics: (note: String, screen: String) -> Unit = { _, _ -> },
    menu: MenuActions = testMenuActions(onSendDiagnostics = onSendDiagnostics),
) = StatusActions(join, joined, access, switch, surfaces, onCreateEvent, onOpenLink, participation, menu)

internal fun testMenuActions(
    onSendDiagnostics: (note: String, screen: String) -> Unit = { _, _ -> },
    onMenuOpen: () -> Unit = {},
    onMenuDismiss: () -> Unit = {},
    onReportBug: () -> Unit = {},
    onOpenLink: (app.snapsync.model.AppLink) -> Unit = {},
    onReportNoticeDismiss: () -> Unit = {},
    onMobileData: (Boolean) -> Unit = {},
) = MenuActions(onSendDiagnostics, onMenuOpen, onMenuDismiss, onReportBug, onOpenLink, onReportNoticeDismiss, onMobileData)

internal fun testJoinGateActions(
    onConfirmJoin: () -> Unit = {},
    onRetryJoin: () -> Unit = {},
    onCancelJoin: () -> Unit = {},
    onRetryLoad: () -> Unit = {},
) = JoinGateActions(onConfirmJoin, onRetryJoin, onCancelJoin, onRetryLoad)

internal fun testJoinedActions(
    onLeaveEvent: () -> Unit = {},
    onShareInvite: () -> Unit = {},
    onQrOpen: () -> Unit = {},
    onQrDismiss: () -> Unit = {},
    onStopSharing: () -> Unit = {},
    onKeepSharing: () -> Unit = {},
    onRenameEvent: (String, String) -> Unit = { _, _ -> },
    onRenameStatusConsumed: () -> Unit = {},
) = JoinedActions(
    onLeaveEvent, onShareInvite, onQrOpen, onQrDismiss, WithdrawalActions(onStopSharing, onKeepSharing), onRenameEvent,
    onRenameStatusConsumed,
)

internal fun testAccessActions(
    onRequestPermission: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onChoosePhotos: () -> Unit = {},
) = AccessActions(onRequestPermission, onOpenSettings, onChoosePhotos)

internal fun testSwitchActions(
    onConfirmSwitch: () -> Unit = {},
    onCancelSwitch: () -> Unit = {},
) = SwitchActions(onConfirmSwitch, onCancelSwitch)

internal fun testSurfaceActions(
    onConfirmLeaveOpen: () -> Unit = {},
    onConfirmLeaveDismiss: () -> Unit = {},
    onRenameOpen: () -> Unit = {},
    onRenameDismiss: () -> Unit = {},
    onOpenReconfigure: () -> Unit = {},
    onCancelReconfigure: () -> Unit = {},
    onReportBugOpen: () -> Unit = {},
    onReportBugDismiss: () -> Unit = {},
) = SurfaceActions(
    onConfirmLeaveOpen, onConfirmLeaveDismiss, onRenameOpen, onRenameDismiss,
    onOpenReconfigure, onCancelReconfigure, onReportBugOpen, onReportBugDismiss,
)

internal fun testParticipationActions(
    choices: RangeChoiceActions = testRangeChoiceActions(),
    onShareOn: (Boolean) -> Unit = {},
    onReceiveOn: (Boolean) -> Unit = {},
    onSaveToAlbum: (Boolean) -> Unit = {},
) = ParticipationActions(choices, onShareOn, onReceiveOn, onSaveToAlbum)

internal fun testRangeChoiceActions(
    onPreset: (app.snapsync.model.RangeChoice) -> Unit = {},
    onCustom: (LocalDateTime, LocalDateTime) -> Unit = { _, _ -> },
) = RangeChoiceActions(onPreset, onCustom)

/** The status screen with inert actions unless a test supplies its own. */
@Composable
internal fun TestStatusScreen(state: UiState, cutoff: CutoffFormatter, actions: StatusActions = testActions()) =
    WithoutKeyboardInsets { StatusScreen(state = state, cutoff = cutoff, actions = actions) }

/**
 * [content] laid out as if no soft keyboard were up — as it is on the JVM and the iOS simulator, which have none.
 *
 * On the Android emulator, typing into a field raises the platform keyboard, and the screen's `safeDrawing` padding
 * shrinks the form's viewport by the keyboard's height — asynchronously, after the typing returns. A test that then
 * taps a calendar day or a wheel row finds it clipped below the new edge (zero visible bounds) and its click lands on
 * nothing: measured on API 36, the viewport went from 450–2042 px to 450–1222 px under a day row at 1255 px. Consuming
 * the keyboard's insets here keeps every screen test on one layout on every target; how the screen makes room for the
 * keyboard is not what these tests are about.
 */
@Composable
internal fun WithoutKeyboardInsets(content: @Composable () -> Unit) =
    Box(Modifier.consumeWindowInsets(WindowInsets.ime)) { content() }
