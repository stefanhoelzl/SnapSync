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
import app.snapsync.presentation.JoinPhase
import app.snapsync.presentation.PendingSwitch
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
import app.snapsync.ui.components.SecondaryButton
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
    // Join-gate actions (capability `join-event`), routed to the container intents.
    onConfirmJoin: () -> Unit = {},
    onCancelJoin: () -> Unit = {},
    onRetryLoad: () -> Unit = {},
    onRetryJoin: () -> Unit = {},
    onConfirmSwitch: () -> Unit = {},
    onCancelSwitch: () -> Unit = {},
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
                is UiState.JoiningEvent ->
                    JoiningEventScreen(state.phase, onConfirmJoin, onCancelJoin, onRetryLoad, onRetryJoin)
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

        // A switch confirmation over the joined screen (scanning a different event while joined).
        (state as? UiState.Joined)?.pendingSwitch?.let { switch ->
            SwitchDialog(
                switch = switch,
                currentEventName = eventName,
                onConfirmSwitch = onConfirmSwitch,
                onCancelSwitch = onCancelSwitch,
                onRetryLoad = onRetryLoad,
                onRetryJoin = onRetryJoin,
            )
        }
    }
}

/**
 * The full-screen "Join event" surface (capability `join-event`): the event summary is the hero, with
 * Join / Cancel pinned to the bottom. Only the confirm ships now; future options (start date,
 * direction, albums, save-to album) slot in as rows in this same column. Renders each [JoinPhase]:
 * loading details, ready-to-join, blocked (invalid invite), a retryable load/commit failure.
 */
@Composable
private fun JoiningEventScreen(
    phase: JoinPhase,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onRetryLoad: () -> Unit,
    onRetryJoin: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (phase) {
                JoinPhase.Loading ->
                    StatusHero(StatusIndicator.Loading, "Loading event details …")
                is JoinPhase.Ready ->
                    AppEventHero(
                        title = phase.name ?: "this event",
                        subtitle = "You've been invited to back up your photos to this event.",
                    )
                JoinPhase.NotFound ->
                    StatusHero(
                        StatusIndicator.Error,
                        "Invalid invite",
                        "This invite is invalid or the event no longer exists.",
                    )
                JoinPhase.LoadFailed ->
                    StatusHero(
                        StatusIndicator.Error,
                        "Couldn't load the event",
                        "Check your connection and try again.",
                    )
                is JoinPhase.Committing ->
                    StatusHero(StatusIndicator.Loading, "Joining …")
                is JoinPhase.CommitFailed ->
                    StatusHero(
                        StatusIndicator.Error,
                        "Couldn't join",
                        "Something went wrong. Try again.",
                    )
            }
        }
        // Actions pinned to the bottom; which ones depend on the phase.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (phase) {
                is JoinPhase.Ready -> {
                    PrimaryButton(label = "Join", onClick = onConfirm)
                    SecondaryButton(label = "Cancel", onClick = onCancel)
                }
                JoinPhase.LoadFailed -> {
                    PrimaryButton(label = "Retry", onClick = onRetryLoad)
                    SecondaryButton(label = "Cancel", onClick = onCancel)
                }
                is JoinPhase.CommitFailed -> {
                    PrimaryButton(label = "Retry", onClick = onRetryJoin)
                    SecondaryButton(label = "Cancel", onClick = onCancel)
                }
                JoinPhase.NotFound ->
                    SecondaryButton(label = "Cancel", onClick = onCancel)
                // In-flight phases offer no actions.
                JoinPhase.Loading, is JoinPhase.Committing -> Unit
            }
        }
    }
}

/**
 * The switch confirmation (a different event scanned while joined) — the leave-style dialog. On confirm
 * it runs leave-then-join. Mirrors the join phases in a compact `AppConfirmDialog`: the loaded phase
 * offers Switch; a load/commit failure offers Retry; a missing event dismisses. Transient
 * loading/committing phases show nothing.
 */
@Composable
private fun SwitchDialog(
    switch: PendingSwitch,
    currentEventName: String?,
    onConfirmSwitch: () -> Unit,
    onCancelSwitch: () -> Unit,
    onRetryLoad: () -> Unit,
    onRetryJoin: () -> Unit,
) {
    val current = currentEventName ?: "this event"
    when (val phase = switch.phase) {
        is JoinPhase.Ready ->
            AppConfirmDialog(
                title = "Leave $current and join ${phase.name ?: "the new event"}?",
                confirmLabel = "Switch",
                cancelLabel = "Cancel",
                onConfirm = onConfirmSwitch,
                onDismiss = onCancelSwitch,
            )
        JoinPhase.NotFound ->
            AppConfirmDialog(
                title = "This invite is invalid or the event no longer exists.",
                confirmLabel = "OK",
                cancelLabel = "Cancel",
                onConfirm = onCancelSwitch,
                onDismiss = onCancelSwitch,
            )
        JoinPhase.LoadFailed ->
            AppConfirmDialog(
                title = "Couldn't load the event. Try again?",
                confirmLabel = "Retry",
                cancelLabel = "Cancel",
                onConfirm = onRetryLoad,
                onDismiss = onCancelSwitch,
            )
        is JoinPhase.CommitFailed ->
            AppConfirmDialog(
                title = "Couldn't switch events. Try again?",
                confirmLabel = "Retry",
                cancelLabel = "Cancel",
                onConfirm = onRetryJoin,
                onDismiss = onCancelSwitch,
            )
        // Transient — no dialog while the details load or the switch commits.
        JoinPhase.Loading, is JoinPhase.Committing -> Unit
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
