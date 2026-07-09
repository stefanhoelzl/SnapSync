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
import app.snapsync.config.Direction
import app.snapsync.permission.PermissionStatus
import app.snapsync.presentation.Arrow
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.JoinPhase
import app.snapsync.presentation.PendingSwitch
import app.snapsync.presentation.SyncHealth
import app.snapsync.presentation.SystemCutoffFormatter
import app.snapsync.presentation.UiState
import app.snapsync.ui.components.AppConfirmDialog
import app.snapsync.ui.components.AppDateTimeField
import app.snapsync.ui.components.AppEventHero
import kotlinx.datetime.LocalDateTime
import app.snapsync.ui.components.AppQrCode
import app.snapsync.ui.components.AccessPrompt
import app.snapsync.ui.components.AppCheckboxRow
import app.snapsync.ui.components.AppDirectionSelector
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
import app.snapsync.ui.components.SyncDirectionChoice

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
    // Join-gate actions (capability `join-event`), routed to the container intents. The confirm/retry
    // actions carry the chosen capture-date cutoff (capability `photo-date-cutoff`; always present),
    // the chosen participation direction (capability `join-event`), and the album opt-in (`saveToAlbum`,
    // capability `event-album`).
    onConfirmJoin: (String, Direction, Boolean) -> Unit = { _, _, _ -> },
    onCancelJoin: () -> Unit = {},
    onRetryLoad: () -> Unit = {},
    onRetryJoin: (String, Direction, Boolean) -> Unit = { _, _, _ -> },
    onConfirmSwitch: (String, Direction) -> Unit = { _, _ -> },
    onCancelSwitch: () -> Unit = {},
    // Bridges the cutoff picker (local wall-clock) to the UTC `…Z` cutoff string; the default is the
    // production impl (device clock + zone), so hosts/tests need not supply one.
    cutoff: CutoffFormatter = SystemCutoffFormatter(),
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
                    JoiningEventScreen(state.phase, cutoff, onConfirmJoin, onCancelJoin, onRetryLoad, onRetryJoin)
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
                // The compact switch path has no album picker — a retry there is album-off.
                onRetryJoin = { cutoff, direction -> onRetryJoin(cutoff, direction, false) },
            )
        }
    }
}

/**
 * The full-screen "Join event" surface (capability `join-event`): the event summary is the hero, with
 * the participation-direction row, the capture-date cutoff row (capability `photo-date-cutoff`), and the
 * save-to-album opt-in (capability `event-album`), with Join / Cancel pinned to the bottom. Further future
 * options slot in as more rows in this same column. Renders each [JoinPhase]: loading details,
 * ready-to-join, blocked (invalid invite), a retryable load/commit failure.
 *
 * The chosen direction and cutoff are held in local state: the direction defaults to [Direction.Both];
 * the cutoff is seeded once, **non-null**, from the loaded default (`createdAt`, already resolved to now by
 * the host when the marker carried none), editable via the date/time picker or snapped to "now", and
 * converted to the UTC `…Z` string on confirm/retry. Both survive Ready → Committing → CommitFailed (the
 * composable stays mounted), so a retry reuses them. The cutoff row is disabled under
 * [Direction.DownloadOnly] (it scopes uploads only).
 */
@Composable
private fun JoiningEventScreen(
    phase: JoinPhase,
    cutoff: CutoffFormatter,
    onConfirm: (String, Direction, Boolean) -> Unit,
    onCancel: () -> Unit,
    onRetryLoad: () -> Unit,
    onRetryJoin: (String, Direction, Boolean) -> Unit,
) {
    // The chosen cutoff is **non-null by construction**, seeded once on first composition: from the loaded
    // phase's `defaultCutoff` (itself non-null — the host resolves an absent/unparseable `createdAt` to
    // now), else from now, which covers a screen mounted straight into CommitFailed. A join with no cutoff
    // is therefore unrepresentable rather than merely guarded — it would upload the whole library
    // (capability `photo-date-cutoff`). "Now" shares too few photos, which a re-join fixes; the opposite
    // error cannot be undone.
    var chosen by remember {
        mutableStateOf((phase as? JoinPhase.Ready)?.let { cutoff.toLocal(it.defaultCutoff) } ?: cutoff.nowLocal())
    }
    var chosenDirection by remember { mutableStateOf(Direction.Both) }
    var chosenSaveToAlbum by remember { mutableStateOf(false) }
    val chosenCutoff: String = cutoff.toCutoff(chosen)

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
                        title = phase.name,
                        subtitle = "You've been invited to share your photos to this event.",
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
                    DirectionRow(selected = chosenDirection, onSelect = { chosenDirection = it })
                    CutoffRow(
                        value = chosen,
                        onValueChange = { chosen = it },
                        onOnlyFromNow = { chosen = cutoff.nowLocal() },
                        // The cutoff scopes uploads only — inert when the user opted out of uploading.
                        enabled = chosenDirection != Direction.DownloadOnly,
                    )
                    // The event-album opt-in (capability `event-album`), default off, offered in every
                    // direction.
                    AppCheckboxRow(
                        label = "Save event photos to an album",
                        checked = chosenSaveToAlbum,
                        onCheckedChange = { chosenSaveToAlbum = it },
                    )
                    PrimaryButton(
                        label = "Join",
                        onClick = { onConfirm(chosenCutoff, chosenDirection, chosenSaveToAlbum) },
                    )
                    SecondaryButton(label = "Cancel", onClick = onCancel)
                }
                JoinPhase.LoadFailed -> {
                    PrimaryButton(label = "Retry", onClick = onRetryLoad)
                    SecondaryButton(label = "Cancel", onClick = onCancel)
                }
                is JoinPhase.CommitFailed -> {
                    PrimaryButton(
                        label = "Retry",
                        onClick = { onRetryJoin(chosenCutoff, chosenDirection, chosenSaveToAlbum) },
                    )
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
 * The participation-direction row on the join surface (capability `join-event`): an **arrows-only**
 * three-way selector (share ↑ / receive ↓ / both ⇅) with a caption above it that **adapts to the
 * selection** (the glyphs alone carry no words). Defaults to [Direction.Both]; the choice is fixed for
 * the membership (a change is a leave-then-rejoin). The screen maps [Direction] to/from the design
 * system's [SyncDirectionChoice] so the components module stays decoupled from the config capability.
 */
@Composable
private fun DirectionRow(selected: Direction, onSelect: (Direction) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusHint(directionCaption(selected))
        AppDirectionSelector(
            selected = selected.toChoice(),
            onSelect = { onSelect(it.toDirection()) },
        )
    }
}

/** The caption above the direction selector, adapting to the current choice (the arrows show no words). */
private fun directionCaption(direction: Direction): String = when (direction) {
    Direction.Both -> "Share your photos and receive the event's photos."
    Direction.UploadOnly -> "Only share your photos — you won't receive the event's."
    Direction.DownloadOnly -> "Only receive the event's photos — you won't share yours."
}

private fun Direction.toChoice(): SyncDirectionChoice = when (this) {
    Direction.Both -> SyncDirectionChoice.BOTH
    Direction.UploadOnly -> SyncDirectionChoice.UPLOAD
    Direction.DownloadOnly -> SyncDirectionChoice.DOWNLOAD
}

private fun SyncDirectionChoice.toDirection(): Direction = when (this) {
    SyncDirectionChoice.BOTH -> Direction.Both
    SyncDirectionChoice.UPLOAD -> Direction.UploadOnly
    SyncDirectionChoice.DOWNLOAD -> Direction.DownloadOnly
}

/**
 * The capture-date cutoff row on the join surface (capability `photo-date-cutoff`): a caption, the
 * date/time picker prefilled to the chosen value (default = the event's `createdAt`), and an "Only from
 * now" shortcut that snaps the cutoff to the current instant. Only photos taken at or after the chosen
 * value are uploaded and shared into the event. [enabled] is false under a download-only membership (the
 * cutoff scopes uploads only): the row stays visible but its inputs are inert.
 */
@Composable
private fun CutoffRow(
    value: LocalDateTime?,
    onValueChange: (LocalDateTime) -> Unit,
    onOnlyFromNow: () -> Unit,
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusHint("Only photos taken after this date are shared to the event.")
        AppDateTimeField(value = value, onValueChange = onValueChange, enabled = enabled)
        SecondaryButton(label = "Only from now", onClick = onOnlyFromNow, enabled = enabled)
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
    onConfirmSwitch: (String, Direction) -> Unit,
    onCancelSwitch: () -> Unit,
    onRetryLoad: () -> Unit,
    onRetryJoin: (String, Direction) -> Unit,
) {
    val current = currentEventName ?: "this event"
    // The compact switch dialog has no picker: it uses the new event's default cutoff (its `createdAt`,
    // or now when that is absent — resolved non-null by the host, capability `photo-date-cutoff`) and the
    // default participation direction ([Direction.Both]).
    // Remembered so a retry after a failed commit reuses it (the CommitFailed phase carries only the name).
    var cutoff by remember { mutableStateOf<String?>(null) }
    when (val phase = switch.phase) {
        is JoinPhase.Ready -> {
            cutoff = phase.defaultCutoff
            AppConfirmDialog(
                title = "Leave $current and join ${phase.name}?",
                confirmLabel = "Switch",
                cancelLabel = "Cancel",
                onConfirm = { onConfirmSwitch(phase.defaultCutoff, Direction.Both) },
                onDismiss = onCancelSwitch,
            )
        }
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
                // The remembered cutoff was set by the Ready phase this commit came from; a retry without
                // one would join at whole-library scope, so it is inert rather than unbounded.
                onConfirm = { cutoff?.let { c -> onRetryJoin(c, Direction.Both) } },
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
