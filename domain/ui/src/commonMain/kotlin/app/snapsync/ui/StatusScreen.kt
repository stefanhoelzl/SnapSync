package app.snapsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.permission.PermissionStatus
import app.snapsync.presentation.UiState
import app.snapsync.ui.components.AppConfirmDialog
import app.snapsync.ui.components.AppTheme
import app.snapsync.ui.components.LeaveButton
import app.snapsync.ui.components.PrimaryButton
import app.snapsync.ui.components.ScreenLayout
import app.snapsync.ui.components.SetupCard
import app.snapsync.ui.components.StatusHero
import app.snapsync.ui.components.StatusIndicator

@Composable
fun StatusScreen(
    state: UiState,
    onRequestPermission: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onLeaveEvent: () -> Unit = {},
    transientError: String? = null,
) {
    AppTheme {
        // Local UI state only: the confirm dialog's visibility never enters UiState or the reduction.
        var confirmingLeave by remember { mutableStateOf(false) }
        // The leave affordance lives in the joined layer only (InProgress / NothingToSync / Completed);
        // the loading, setup-gate, joining, and join-failed states show none.
        val leaveAction: (@Composable () -> Unit)? = if (state.isJoinedLayer) {
            { LeaveButton(description = "Leave event", onClick = { confirmingLeave = true }) }
        } else {
            null
        }

        ScreenLayout(title = "SnapSync", bottomEndAction = leaveAction) {
            when (state) {
                UiState.Loading ->
                    StatusHero(StatusIndicator.Loading, "Loading …")
                UiState.Joining ->
                    StatusHero(StatusIndicator.Loading, "Checking what's already backed up …")
                UiState.JoinFailed ->
                    StatusHero(StatusIndicator.Error, "Couldn't reach the server", "Scan the event QR code again")
                is UiState.Setup ->
                    SetupGate(state, onRequestPermission, onOpenSettings, transientError)
                is UiState.PermissionBlocked ->
                    PermissionBlocked(state.permission, onRequestPermission, onOpenSettings)
                is UiState.InProgress ->
                    StatusHero(
                        StatusIndicator.InProgress,
                        "${state.synced} of ${state.total} images synced",
                        // Second caption: how many are uploading right now (omitted at 0 — e.g.
                        // photos discovered but not yet started), then the last-sync age (absent at a
                        // virgin "0 of N"). When neither applies there is no detail line.
                        inProgressCaption(state.inProgress, state.finishedAgo),
                    )
                UiState.NothingToSync ->
                    StatusHero(StatusIndicator.Complete, "Nothing to sync yet")
                is UiState.Completed ->
                    StatusHero(StatusIndicator.Complete, "${state.total} images synced", state.finishedAgo)
            }
        }

        if (confirmingLeave) {
            AppConfirmDialog(
                title = "Leave event?",
                confirmLabel = "Confirm",
                cancelLabel = "Cancel",
                onConfirm = {
                    confirmingLeave = false
                    onLeaveEvent()
                },
                onDismiss = { confirmingLeave = false },
            )
        }
    }
}

// The joined layer — config + permission satisfied and the join settled — is the only place the
// leave affordance appears. Loading, the setup gate, and the join phase (Joining/JoinFailed) show none.
private val UiState.isJoinedLayer: Boolean
    get() = this is UiState.InProgress || this == UiState.NothingToSync || this is UiState.Completed

// The InProgress detail line: the "{n} in progress" label only when something is actively uploading,
// joined to the last-sync age with " · ". Null (no detail line) when neither is present.
private fun inProgressCaption(inProgress: Int, finishedAgo: String?): String? {
    val active = if (inProgress > 0) "$inProgress in progress" else null
    return listOfNotNull(active, finishedAgo).joinToString(" · ").ifEmpty { null }
}

/**
 * Permission blocked while an event is connected: the status screen hosts the permission affordance
 * as a hero plus a single CTA, switching on the (non-granted) status. NOT_DETERMINED primes the
 * first grant (neutral photos glyph, "Allow access"); DENIED points at Settings (error glyph). No
 * counts — the live gallery total is unavailable without photo access.
 */
@Composable
private fun PermissionBlocked(
    permission: PermissionStatus,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (permission) {
            PermissionStatus.NOT_DETERMINED -> {
                StatusHero(
                    StatusIndicator.Photos,
                    "Allow photo access",
                    "SnapSync needs your photo library to back it up.",
                )
                PrimaryButton("Allow access", onRequestPermission)
            }
            // GRANTED never reaches this screen (the reduction falls through to the sync hero); render
            // the DENIED settings path for it too rather than introduce an unreachable branch.
            PermissionStatus.DENIED, PermissionStatus.GRANTED -> {
                StatusHero(
                    StatusIndicator.Error,
                    "Photo access turned off",
                    "SnapSync needs photo access to continue backing up your library.",
                )
                PrimaryButton("Open Settings", onOpenSettings)
            }
        }
    }
}

/**
 * The setup gate: a stack of two checkable cards. Storage is passive (completed by an external QR
 * scan, so no button) — its detail flips to [transientError] when a bad deeplink arrives. Photo
 * access carries the permission CTA. Each card collapses to a check once satisfied.
 */
@Composable
private fun SetupGate(
    state: UiState.Setup,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    transientError: String?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.storageConnected) {
            SetupCard(StatusIndicator.Success, "Storage connected")
        } else {
            SetupCard(
                indicator = if (transientError != null) StatusIndicator.Error else StatusIndicator.Waiting,
                title = "Connect your storage",
                detail = transientError ?: "Open the Camera app and scan your SnapSync QR code.",
            )
        }

        when (state.permission) {
            PermissionStatus.GRANTED ->
                SetupCard(StatusIndicator.Success, "Photo access granted")
            PermissionStatus.NOT_DETERMINED ->
                SetupCard(
                    indicator = StatusIndicator.Photos,
                    title = "Allow photo access",
                    detail = "SnapSync needs your photo library to back it up.",
                    action = { PrimaryButton("Allow access", onRequestPermission) },
                )
            PermissionStatus.DENIED ->
                SetupCard(
                    indicator = StatusIndicator.Error,
                    title = "Photo access denied",
                    detail = "Turn on photo access in Settings.",
                    action = { PrimaryButton("Open Settings", onOpenSettings) },
                )
        }
    }
}
