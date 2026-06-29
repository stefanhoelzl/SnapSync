package app.snapsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import app.snapsync.ui.components.AppQrCode
import app.snapsync.ui.components.AppTextField
import app.snapsync.ui.components.AppTheme
import app.snapsync.ui.components.LeaveButton
import app.snapsync.ui.components.PrimaryButton
import app.snapsync.ui.components.ScreenLayout
import app.snapsync.ui.components.ShareButton
import app.snapsync.ui.components.StatusHero
import app.snapsync.ui.components.StatusIndicator

@Composable
fun StatusScreen(
    state: UiState,
    onRequestPermission: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onLeaveEvent: () -> Unit = {},
    onShareInvite: () -> Unit = {},
    inviteUrl: String? = null,
    onCreateEvent: (String) -> Unit = {},
    transientError: String? = null,
) {
    AppTheme {
        // Local UI state only: the confirm dialog's visibility never enters UiState or the reduction.
        var confirmingLeave by remember { mutableStateOf(false) }
        // The invite + leave affordances live in the joined layer only (InProgress / NothingToSync /
        // Completed); the loading, setup-gate, and permission-blocked states show none. Share renders
        // only when an invite URL exists (always true in the joined layer, where config is present);
        // both ride the bottom-end action cluster, share before leave.
        val bottomEndActions: (@Composable () -> Unit)? = if (state.isJoinedLayer) {
            {
                if (inviteUrl != null) {
                    ShareButton(description = "Share invite link", onClick = onShareInvite)
                }
                LeaveButton(description = "Leave event", onClick = { confirmingLeave = true })
            }
        } else {
            null
        }

        ScreenLayout(title = "SnapSync", bottomEndActions = bottomEndActions) {
            // In the joined layer the join QR sits above the status hero, so others can scan to join.
            if (state.isJoinedLayer && inviteUrl != null) {
                AppQrCode(content = inviteUrl, caption = "Scan to join this event")
                Spacer(Modifier.height(24.dp))
            }
            when (state) {
                UiState.Loading ->
                    StatusHero(StatusIndicator.Loading, "Loading …")
                is UiState.CreateEvent ->
                    CreateEventScreen(state, onCreateEvent, transientError)
                UiState.CreatingEvent ->
                    StatusHero(StatusIndicator.Loading, "Creating your event …")
                is UiState.PermissionBlocked ->
                    PermissionBlocked(state.permission, onRequestPermission, onOpenSettings)
                is UiState.InProgress ->
                    StatusHero(
                        StatusIndicator.InProgress,
                        "${state.synced} of ${state.total} images synced",
                        // Second caption: how many are uploading right now (omitted at 0 — e.g.
                        // photos discovered but not yet started). When none are, there is no detail line.
                        inProgressCaption(state.inProgress),
                    )
                UiState.NothingToSync ->
                    StatusHero(StatusIndicator.Complete, "Nothing to sync yet")
                is UiState.Completed ->
                    StatusHero(StatusIndicator.Complete, "${state.total} images synced")
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

// The joined layer — config + permission satisfied — is the only place the leave affordance appears.
// Loading and the setup gate show none.
private val UiState.isJoinedLayer: Boolean
    get() = this is UiState.InProgress || this == UiState.NothingToSync || this is UiState.Completed

// The InProgress detail line: the "{n} in progress" label only when something is actively uploading,
// else null (no detail line).
private fun inProgressCaption(inProgress: Int): String? =
    if (inProgress > 0) "$inProgress in progress" else null

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
 * The create-event landing layer (event-creation-ui): shown while no event is connected. A name
 * field + Create button mint and auto-join a new event, with a passive hint that scanning a QR in
 * Camera joins an existing one. The name lives in local Compose state (only the submitted, trimmed
 * value crosses the container); Create is disabled until the trimmed name is non-empty, and the
 * field caps at 100 characters. The single inline error beneath the field carries either a transient
 * invalid-deeplink flash ([transientError]) or the last create failure's copy ([UiState.CreateEvent.error]).
 */
@Composable
private fun CreateEventScreen(
    state: UiState.CreateEvent,
    onCreateEvent: (String) -> Unit,
    transientError: String?,
) {
    var name by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusHero(
            StatusIndicator.Photos,
            "Create an event",
            "Or scan an event's QR code in the Camera app to join it.",
        )
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
    }
}

// Mirrors the backend's name cap (trimmed, non-empty, ≤100) so a server 400 is near-unreachable.
private const val EVENT_NAME_MAX_LENGTH = 100
