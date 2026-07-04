package app.snapsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import app.snapsync.presentation.Arrow
import app.snapsync.presentation.SyncHealth
import app.snapsync.presentation.UiState
import app.snapsync.ui.components.AppConfirmDialog
import app.snapsync.ui.components.AppEventHero
import app.snapsync.ui.components.AppQrCode
import app.snapsync.ui.components.AccessPrompt
import app.snapsync.ui.components.AppStatusLine
import app.snapsync.ui.components.AppSyncStatus
import app.snapsync.ui.components.AppTextField
import app.snapsync.ui.components.AppTheme
import app.snapsync.ui.components.ArrowLevel
import app.snapsync.ui.components.LeaveButton
import app.snapsync.ui.components.PrimaryButton
import app.snapsync.ui.components.ScreenLayout
import app.snapsync.ui.components.ShareButton
import app.snapsync.ui.components.StatusHero
import app.snapsync.ui.components.StatusHint
import app.snapsync.ui.components.StatusIndicator

@Composable
fun StatusScreen(
    state: UiState,
    onRequestPermission: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onLeaveEvent: () -> Unit = {},
    onShareInvite: () -> Unit = {},
    inviteUrl: String? = null,
    // The joined event's name (fetched by id), shown as the heading; null until fetched.
    eventName: String? = null,
    onCreateEvent: (String) -> Unit = {},
    transientError: String? = null,
) {
    AppTheme {
        // Local UI state only: the confirm dialog's visibility never enters UiState or the reduction.
        var confirmingLeave by remember { mutableStateOf(false) }

        // The invite + leave affordances live in the joined layer (config present) — any health,
        // including NeedsAccess: sharing needs no photo access. Loading and the create layer show none.
        val joined = state is UiState.Joined
        val bottomActions: (@Composable () -> Unit)? = if (joined) {
            {
                if (inviteUrl != null) {
                    ShareButton(description = "Share invite link", onClick = onShareInvite)
                }
                LeaveButton(description = "Leave event", onClick = { confirmingLeave = true })
            }
        } else {
            null
        }

        // The app-name nav label is always "SnapSync"; the joined event's name is the prominent heading.
        ScreenLayout(
            title = "SnapSync",
            heading = if (joined) eventName else null,
            bottomActions = bottomActions,
        ) {
            when (state) {
                is UiState.CreateEvent ->
                    CreateEventScreen(state, onCreateEvent, transientError)
                UiState.CreatingEvent ->
                    StatusHero(StatusIndicator.Loading, "Creating your event …")
                is UiState.Joined ->
                    JoinedLayer(state.health, inviteUrl, onRequestPermission, onOpenSettings)
            }
        }

        if (confirmingLeave) {
            AppConfirmDialog(
                title = "Leave this event?",
                confirmLabel = "Leave",
                cancelLabel = "Stay",
                onConfirm = {
                    confirmingLeave = false
                    onLeaveEvent()
                },
                onDismiss = { confirmingLeave = false },
            )
        }
    }
}

/**
 * The joined-layer event home: the join QR is the hero, the one-line sync health beneath it (the event
 * name is the screen heading above, per [ScreenLayout]). The permission affordance is folded into the
 * status line (the `NeedsAccess` variant), tappable to the right action — never a hero-replacing gate.
 */
@Composable
private fun JoinedLayer(
    health: SyncHealth,
    inviteUrl: String?,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (inviteUrl != null) {
            AppQrCode(content = inviteUrl, caption = "Scan to join this event")
        }
        AppStatusLine(
            status = health.toAppSyncStatus(),
            onAttentionClick = {
                if (health is SyncHealth.NeedsAccess) {
                    if (health.permission == PermissionStatus.NOT_DETERMINED) {
                        onRequestPermission()
                    } else {
                        onOpenSettings()
                    }
                }
            },
        )
    }
}

private fun SyncHealth.toAppSyncStatus(): AppSyncStatus = when (this) {
    is SyncHealth.NeedsAccess -> AppSyncStatus.NeedsAccess(
        if (permission == PermissionStatus.NOT_DETERMINED) AccessPrompt.ALLOW else AccessPrompt.SETTINGS,
    )
    SyncHealth.Loading -> AppSyncStatus.Loading
    SyncHealth.InSync -> AppSyncStatus.InSync
    is SyncHealth.Syncing -> AppSyncStatus.Syncing(upload.toLevel(), download.toLevel())
}

private fun Arrow.toLevel(): ArrowLevel = when (this) {
    Arrow.HIDDEN -> ArrowLevel.HIDDEN
    Arrow.STATIC -> ArrowLevel.STATIC
    Arrow.PULSING -> ArrowLevel.PULSING
}

/**
 * The create-event landing layer (event-creation-ui): the hero sits centered while the name field +
 * Create button + scan hint are pinned to the bottom. Framed as sharing (not backup). The name lives
 * in local Compose state (only the submitted, trimmed value crosses the container); Create is disabled
 * until the trimmed name is non-empty, and the field caps at 100 characters.
 */
@Composable
private fun CreateEventScreen(
    state: UiState.CreateEvent,
    onCreateEvent: (String) -> Unit,
    transientError: String?,
) {
    var name by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize()) {
        // Hero centered in the space above the inputs.
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppEventHero(
                title = "Start an event",
                subtitle = "Name it and share the code — everyone's photos land in one place.",
            )
        }
        // Inputs pinned to the bottom.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "Event name",
                maxLength = EVENT_NAME_MAX_LENGTH,
                errorText = transientError ?: state.error,
            )
            PrimaryButton(
                label = "Create event",
                onClick = { onCreateEvent(name) },
                enabled = name.isNotBlank(),
            )
            StatusHint("Or scan a QR code in the Camera app to join one.")
        }
    }
}

// Mirrors the backend's name cap (trimmed, non-empty, ≤100) so a server 400 is near-unreachable.
private const val EVENT_NAME_MAX_LENGTH = 100
