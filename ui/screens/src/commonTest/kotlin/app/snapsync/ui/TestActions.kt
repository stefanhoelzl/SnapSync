package app.snapsync.ui

import androidx.compose.runtime.Composable
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
    onSendDiagnostics: ((note: String, screen: String) -> Unit)? = null,
) = StatusActions(join, joined, access, switch, surfaces, onCreateEvent, onOpenLink, participation, onSendDiagnostics)

internal fun testJoinGateActions(
    onConfirmJoin: () -> Unit = {},
    onRetryJoin: () -> Unit = {},
    onAcknowledgeAccess: () -> Unit = {},
    onCancelJoin: () -> Unit = {},
    onRetryLoad: () -> Unit = {},
) = JoinGateActions(onConfirmJoin, onRetryJoin, onAcknowledgeAccess, onCancelJoin, onRetryLoad)

internal fun testJoinedActions(
    onLeaveEvent: () -> Unit = {},
    onShareInvite: () -> Unit = {},
    onReconfigure: () -> Unit = {},
    onRenameEvent: (String, String) -> Unit = { _, _ -> },
    onRenameStatusConsumed: () -> Unit = {},
) = JoinedActions(onLeaveEvent, onShareInvite, onReconfigure, onRenameEvent, onRenameStatusConsumed)

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
    onFromPreset: (app.snapsync.model.FromChoice) -> Unit = {},
    onFromCustom: (LocalDateTime) -> Unit = {},
    onUntilPreset: (app.snapsync.model.UntilChoice) -> Unit = {},
    onUntilCustom: (LocalDateTime) -> Unit = {},
) = RangeChoiceActions(onFromPreset, onFromCustom, onUntilPreset, onUntilCustom)

/** The status screen with inert actions unless a test supplies its own. */
@Composable
internal fun TestStatusScreen(state: UiState, cutoff: CutoffFormatter, actions: StatusActions = testActions()) =
    StatusScreen(state = state, cutoff = cutoff, actions = actions)
