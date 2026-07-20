package app.snapsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.model.Direction
import app.snapsync.model.PermissionStatus
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.JoinPhase
import app.snapsync.presentation.PendingSwitch
import app.snapsync.presentation.SyncHealth
import app.snapsync.presentation.UiState
import app.snapsync.ui.components.AppConfirmDialog
import app.snapsync.ui.components.AppDestructiveConfirmDialog
import app.snapsync.ui.components.AppEventHero
import app.snapsync.ui.components.AppExplainer
import kotlinx.datetime.LocalDateTime
import app.snapsync.ui.components.AppQrCode
import app.snapsync.ui.components.AccessPrompt
import app.snapsync.ui.components.AppCheckboxRow
import app.snapsync.ui.components.AppCutoffSelector
import app.snapsync.ui.components.AppEventStartRow
import app.snapsync.ui.components.CutoffChoice
import app.snapsync.ui.components.AppDirectionSelector
import app.snapsync.ui.components.AppStatusLine
import app.snapsync.ui.components.AppSyncStatus
import app.snapsync.ui.components.AppTextField
import app.snapsync.ui.components.AppTheme
import app.snapsync.ui.components.LeaveButton
import app.snapsync.ui.components.PrimaryButton
import app.snapsync.ui.components.ScreenLayout
import app.snapsync.ui.components.SecondaryButton
import app.snapsync.ui.components.ShareButton
import app.snapsync.ui.components.StatusHero
import app.snapsync.ui.components.SecondaryButton
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
    onCreateEvent: (String, LocalDateTime) -> Unit = { _, _ -> },
    transientError: String? = null,
    // Join-gate actions (capability `join-event`), routed to the container intents. The confirm/retry
    // actions carry the chosen capture-date cutoff (capability `photo-selection-policy`; always present),
    // the chosen participation direction (capability `join-event`), and the album opt-in (`saveToAlbum`,
    // capability `event-album`).
    onConfirmJoin: (String, Direction, Boolean) -> Unit = { _, _, _ -> },
    // The photo-access explainer's confirm: requests permission, then advances to the confirm surface.
    // The only route from the join gate to the system dialog (capability `join-event`).
    onAcknowledgeAccess: () -> Unit = {},
    // The joined layer's "Choose more photos" tap under a partial grant (capability
    // `limited-photo-access`): presents the system limited-library picker.
    onChoosePhotos: () -> Unit = {},
    onCancelJoin: () -> Unit = {},
    onRetryLoad: () -> Unit = {},
    onRetryJoin: (String, Direction, Boolean) -> Unit = { _, _, _ -> },
    onConfirmSwitch: (String, Direction) -> Unit = { _, _ -> },
    onCancelSwitch: () -> Unit = {},
    // Bridges the cutoff picker (local wall-clock) to the UTC `…Z` cutoff string. Required — with NO
    // system-reading default (migration step 9): the host binds the `Clock`/`TimeZoneSource` ports
    // (production) or a fixed instant/zone (tests); this screen holds no clock or timezone knowledge.
    cutoff: CutoffFormatter,
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
                    CreateEventScreen(state, onCreateEvent, transientError, cutoff)
                UiState.CreatingEvent ->
                    StatusHero(StatusIndicator.Loading, "Creating your event …")
                is UiState.JoiningEvent ->
                    JoiningEventScreen(
                        state.phase, cutoff, onConfirmJoin, onAcknowledgeAccess,
                        onCancelJoin, onRetryLoad, onRetryJoin,
                    )
                is UiState.Joined ->
                    JoinedLayer(
                        state.health, inviteUrl, onRequestPermission, onOpenSettings,
                        state.canChoosePhotos, onChoosePhotos, cutoff,
                    )
            }
        }

        if (confirmingLeave) {
            AppDestructiveConfirmDialog(
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
 * The loaded `createdAt` default, carried by the two phases that have one. `null` everywhere else
 * (`Loading` before the fetch resolves; `NotFound`/`LoadFailed`; `Committing`/`CommitFailed` after the
 * confirm) — which is why the cutoff row seeds from the first phase that *does* carry one, not from
 * whichever phase the screen happened to mount at.
 */
/**
 * The event's start, from whichever phase carries it (capability `photo-selection-policy`).
 *
 * Unlike the seed it replaces, this covers **Committing and CommitFailed too**. Those phases carry
 * `startsAt` precisely because a Retry commits WITHOUT passing back through the loaded phase — reading it
 * only from `Ready` would make a retry derive its cutoff from `now` instead of the start the user chose,
 * silently discarding their selection at the one moment they are already recovering from a failure.
 */
private fun JoinPhase.startsAt(): String? = when (this) {
    is JoinPhase.ExplainAccess -> startsAt
    is JoinPhase.Ready -> startsAt
    is JoinPhase.Committing -> startsAt
    is JoinPhase.CommitFailed -> startsAt
    JoinPhase.Loading, JoinPhase.NotFound, JoinPhase.LoadFailed -> null
}

/**
 * The full-screen "Join event" surface (capability `join-event`): the event summary is the hero, with
 * the participation-direction row, the capture-date cutoff row (capability `photo-selection-policy`), and the
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
    onAcknowledgeAccess: () -> Unit,
    onCancel: () -> Unit,
    onRetryLoad: () -> Unit,
    onRetryJoin: (String, Direction, Boolean) -> Unit,
) {
    // The cutoff is one of exactly two presets (capability `photo-selection-policy`), defaulting to the event's
    // start. A join with no cutoff is unrepresentable rather than merely guarded — it would upload the
    // whole library.
    //
    // What is REMEMBERED is the preset, not an instant — the instant is derived fresh from the phase on
    // every composition. That sidesteps by construction the seeding bug a `remember`-ed instant had: this
    // screen mounts at `Loading` (what `startPending` sets before the details fetch), so anything seeded on
    // first composition captured `now` and never re-ran when the loaded phase arrived. There is nothing to
    // seed here, so there is nothing to go stale.
    var chosenPreset by remember { mutableStateOf(CutoffChoice.EVENT_START) }
    var chosenDirection by remember { mutableStateOf(Direction.Both) }
    var chosenSaveToAlbum by remember { mutableStateOf(false) }

    // The event's start, as a local wall-clock value. Non-null on Ready (the host guarantees `startsAt`);
    // a screen mounted straight into CommitFailed falls back to now, which is inert there — that phase
    // renders no cutoff row, and its Retry re-sends the cutoff the Ready phase already committed.
    val eventStart: LocalDateTime =
        phase.startsAt()?.let { cutoff.toLocal(it) } ?: cutoff.nowLocal()
    val nowLocal: LocalDateTime = cutoff.nowLocal()

    // Pre-start, "Now" would clamp to the very same instant as "Event start" (`max(now, startsAt) ==
    // startsAt`), so it is offered disabled rather than as a button that visibly does nothing.
    val eventHasStarted: Boolean = cutoff.toCutoff(eventStart) <= cutoff.nowCutoff()

    // What the member is actually committing to — rendered as the selector's label, so the value is never
    // hidden. `JoinEvent` clamps this to `max(chosen, startsAt)` on the far side; picking EVENT_START (or
    // NOW while the event has not started) simply lands on the floor already.
    val resulting: LocalDateTime = when {
        chosenPreset == CutoffChoice.EVENT_START || !eventHasStarted -> eventStart
        else -> nowLocal
    }
    val chosenCutoff: String = cutoff.toCutoff(resulting)

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (phase) {
                JoinPhase.Loading ->
                    StatusHero(StatusIndicator.Loading, "Loading event details …")
                // The photo-access explainer, ahead of the confirm and ahead of the system dialog
                // (capability `join-event`). Share-first: the automatic sharing is the half that deserves
                // informed consent, so it leads. Direction-neutral, because the direction row comes next —
                // and full access is genuinely needed for both halves. The event is deliberately not named:
                // this is a statement about what the app does. No "cutoff", no "upload", no "backup".
                is JoinPhase.ExplainAccess ->
                    AppExplainer(
                        headline = "Photo access",
                        // The system dialog's outcomes are a real choice (capability `join-event`):
                        // allowing all photos shares automatically; picking specific photos is a
                        // first-class alternative (capability `limited-photo-access`), not a
                        // degraded one.
                        paragraphs = listOf(
                            "Photos you take will be shared automatically with everyone in the event.",
                            "SnapSync needs access to your photo library to do this — and to save the " +
                                "photos other members share with you.",
                            "You can allow all photos, or pick exactly which ones to share — and add " +
                                "more anytime.",
                            "Only photos taken after the date you pick next are shared.",
                        ),
                    )
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
                // "I understand" is the ONLY path from the join gate to the system dialog (CTA-only
                // priming). Cancel is the same cancel every other phase pins — it abandons the join.
                is JoinPhase.ExplainAccess -> {
                    PrimaryButton(label = "I understand", onClick = onAcknowledgeAccess)
                    SecondaryButton(label = "Cancel", onClick = onCancel)
                }
                is JoinPhase.Ready -> {
                    DirectionRow(selected = chosenDirection, onSelect = { chosenDirection = it })
                    CutoffRow(
                        selected = chosenPreset,
                        onSelect = { chosenPreset = it },
                        resulting = resulting,
                        // Pre-start, "Now" clamps to the same instant as "Event start" — offered disabled.
                        nowAvailable = eventHasStarted,
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
 * The capture-date cutoff row on the join surface (capability `photo-selection-policy`): a caption and a
 * two-preset selector — **Now** or **Event start** — with the resulting instant shown as a label, so the
 * member always sees the value they are committing to. Only photos taken at or after it are uploaded and
 * shared into the event.
 *
 * The free date+time picker that used to live here is gone. With the event's `startsAt` supplying both the
 * default *and* a floor, an arbitrary picker could only offer values the clamp would reject (anything
 * below the floor) — so the row collapses to a one-tap decision.
 *
 * [nowAvailable] is false before the event starts ("Now" would clamp to the same instant as "Event
 * start"). [enabled] is false under a download-only membership (the cutoff scopes uploads only): the row
 * stays visible but its inputs are inert.
 */
@Composable
private fun CutoffRow(
    selected: CutoffChoice,
    onSelect: (CutoffChoice) -> Unit,
    resulting: LocalDateTime,
    nowAvailable: Boolean = true,
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusHint("Only photos taken after this date are shared to the event.")
        AppCutoffSelector(
            selected = selected,
            onSelect = onSelect,
            resulting = resulting,
            nowAvailable = nowAvailable,
            enabled = enabled,
        )
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
    // The compact switch dialog has no picker: it uses the new event's default cutoff — its `startsAt`,
    // which is also the FLOOR, so the switch lands exactly on it (capability `photo-selection-policy`) — and the
    // default participation direction ([Direction.Both]).
    // Remembered so a retry after a failed commit reuses it (the CommitFailed phase carries name+startsAt).
    var cutoff by remember { mutableStateOf<String?>(null) }
    when (val phase = switch.phase) {
        // Unreachable. The photo-access explainer is a FIRST-join surface: `readyOrExplain` emits it only
        // when `config == null`, and a switch by definition has a config. Anyone switching is already on the
        // joined layer, where the `NeedsAccess` affordance handles a missing grant — so no explanation is
        // lost. Kotlin's exhaustive `when` requires the branch; the container test "a switch never explains"
        // is what keeps it dead (capability `join-event`).
        is JoinPhase.ExplainAccess -> Unit
        is JoinPhase.Ready -> {
            cutoff = phase.startsAt
            AppDestructiveConfirmDialog(
                title = "Leave $current and join ${phase.name}?",
                confirmLabel = "Switch",
                cancelLabel = "Cancel",
                onConfirm = { onConfirmSwitch(phase.startsAt, Direction.Both) },
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
    canChoosePhotos: Boolean,
    onChoosePhotos: () -> Unit,
    cutoff: CutoffFormatter,
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
            status = health.toAppSyncStatus(cutoff),
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
        // The partial-grant resting affordance (capability `limited-photo-access`): present in every
        // health, OUTSIDE the status-line slot — the selection is the membership's scope, and widening
        // it is an ordinary action, not a problem to fix.
        if (canChoosePhotos) {
            SecondaryButton(label = "Choose more photos", onClick = onChoosePhotos)
        }
    }
}

private fun SyncHealth.toAppSyncStatus(cutoff: CutoffFormatter): AppSyncStatus = when (this) {
    is SyncHealth.NeedsAccess -> AppSyncStatus.NeedsAccess(
        if (permission == PermissionStatus.NOT_DETERMINED) AccessPrompt.ALLOW else AccessPrompt.SETTINGS,
    )
    // The clock line renders the start in the DEVICE's local zone — a guest in another timezone sees the
    // event begin at their own wall-clock time, which is the honest reading of an instant. An unparseable
    // startsAt cannot occur (the details source normalizes it, and the config decoder requires it), so an
    // unreadable one degrades to the neutral first frame rather than crashing the joined screen.
    is SyncHealth.NotStarted ->
        cutoff.toLocal(startsAt)?.let { AppSyncStatus.NotStarted(it) } ?: AppSyncStatus.Loading
    SyncHealth.Unattested -> AppSyncStatus.CannotVerifyDevice
    SyncHealth.Loading -> AppSyncStatus.Loading
    SyncHealth.InSync -> AppSyncStatus.InSync
    // Since the step-9 Arrow/ArrowLevel unification both sides speak `model/`'s Arrow — no mapping.
    is SyncHealth.Syncing -> AppSyncStatus.Syncing(upload, download)
}

/**
 * The create-event landing layer (event-creation-ui): the hero sits centered while the name field + start
 * row + Create button + scan hint are pinned to the bottom. Framed as sharing (not backup). The name and
 * the start live in local Compose state (only the submitted values cross the container); Create is
 * disabled until the trimmed name is non-empty, and the field caps at 100 characters.
 *
 * The start defaults to **now, frozen at first composition** (`remember { … }`, not re-derived at submit).
 * The label is the screen's whole statement about what will be sent, so a value that silently drifted
 * between being displayed and being posted would make the screen lie. A slow typer therefore sets a start
 * a few minutes in the past — harmless, since they are at their own event.
 */
@Composable
private fun CreateEventScreen(
    state: UiState.CreateEvent,
    onCreateEvent: (String, LocalDateTime) -> Unit,
    transientError: String?,
    cutoff: CutoffFormatter,
) {
    var name by remember { mutableStateOf("") }
    var startsAt by remember { mutableStateOf(cutoff.nowLocal()) }
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
            AppEventStartRow(value = startsAt, onValueChange = { startsAt = it })
            PrimaryButton(
                label = "Create event",
                onClick = { onCreateEvent(name, startsAt) },
                enabled = name.isNotBlank(),
            )
            StatusHint("Or scan a QR code in the Camera app to join one.")
        }
    }
}

// Mirrors the backend's name cap (trimmed, non-empty, ≤100) so a server 400 is near-unreachable.
private const val EVENT_NAME_MAX_LENGTH = 100
