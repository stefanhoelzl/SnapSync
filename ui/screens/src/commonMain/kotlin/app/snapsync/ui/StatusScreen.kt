package app.snapsync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import app.snapsync.model.EventConfig
import app.snapsync.model.PermissionStatus
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.feature.membership.RenameStatus
import app.snapsync.presentation.UiState
import app.snapsync.ui.components.AppTextPromptSheet
import app.snapsync.ui.components.AppDestructiveConfirmDialog
import app.snapsync.ui.components.AppTheme
import app.snapsync.ui.components.LeaveButton
import app.snapsync.ui.components.ScreenLayout
import app.snapsync.ui.components.SettingsButton
import app.snapsync.ui.components.ShareButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue


/**
 * How far past the event's start "no ceiling" is rendered as. The picker needs a concrete upper bound
 * to lay out against, and a century clear of the window is the same answer as "unbounded" for every
 * event this app models (at most 30 days long) while remaining a real date the picker can draw.
 */
// `internal`, not `private`, for one reason: Kotlin's top-level `private` is FILE-private, and this
// screen was split out of a single 1489-line file into one file per surface. Every symbol below that
// another of those files reaches is widened to module scope and no further — `:ui:screens` contains
// nothing but these screens, so `internal` here is the same audience `private` had before the split.
internal const val NO_CEILING_YEARS = 100


/**
 * The status screen's own visibility flags.
 *
 * Screen-local navigation, every one of them: opening a confirm dialog, a rename sheet, a bug-report
 * sheet or the reconfigure surface touches no port and is not a state of the sync, so none belongs in
 * `UiState` or in the reduction (capability `reconfigure-membership` calls this "local Compose
 * navigation"; `diagnostic-logging` and `event-rename` make the same call for their sheets).
 *
 * A holder rather than four separate `var`s because the overlays that read them are a composable of
 * their own: four flags would otherwise cross that boundary as four values and four setters.
 */
private class StatusOverlayState {
    var confirmingLeave by mutableStateOf(false)
    var reportingBug by mutableStateOf(false)
    var reconfiguring by mutableStateOf(false)

    /** The typed name lives inside the sheet; the screen learns it only on confirm. */
    var renaming by mutableStateOf(false)
}

@Composable
fun StatusScreen(
    state: UiState,
    // The joined membership's current settings, for the in-place reconfigure surface (capability
    // `reconfigure-membership`); null when no event is configured. The settings gear opens a full-screen
    // surface pre-filled from this, and Save fires [onReconfigure]. A screen param like [inviteUrl] —
    // opening/closing the surface is screen-local navigation, so no external "open" callback is threaded.
    membership: EventConfig? = null,
    inviteUrl: String? = null,
    // The joined event's name (fetched by id), shown as the heading; null until fetched.
    eventName: String? = null,
    transientError: String? = null,
    // Bridges the cutoff picker (local wall-clock) to the UTC `…Z` cutoff string. Required — with NO
    // system-reading default (migration step 9): the host binds the `Clock`/`TimeZoneSource` ports
    // (production) or a fixed instant/zone (tests); this screen holds no clock or timezone knowledge.
    cutoff: CutoffFormatter,
    // The current photo-access grant, threaded purely as a recompute trigger for the count: a late resolve
    // (the first-join dialog is answered a beat after Ready renders) must make the count appear.
    photoPermission: PermissionStatus = PermissionStatus.GRANTED,
    // The rename lifecycle. `InFlight` makes the dialog busy, `Succeeded` closes it, `Failed` keeps it
    // open with an error banner. A screen param like [eventName] — it is NOT part of UiState.
    renameStatus: RenameStatus = RenameStatus.Idle,
    // The eighteen things this screen can ask for, bundled (see [StatusActions]). Defaulted, so a host
    // that wires none of them — the forge reviewing a forged state — constructs nothing.
    actions: StatusActions = StatusActions(),
) {
    AppTheme {
        // The screen's own visibility flags, held together (see [StatusOverlayState]). Screen-local
        // navigation: none of these enters UiState or the reduction, because opening a dialog is not a
        // state of the sync. Grouped rather than left as four separate `var`s so the overlay block below
        // can be a composable of its own without threading four values and four setters through it.
        val overlays = remember { StatusOverlayState() }

        val joined = state is UiState.Joined
        // The reconfigure surface renders only while joined with a known membership; if the config drops
        // (a leave lands) the flag is reset so a later rejoin does not reopen it.
        val reconfigureActive = joined && overlays.reconfiguring && membership != null
        LaunchedEffect(joined) {
            if (!joined) {
                overlays.reconfiguring = false
                overlays.renaming = false
            }
        }

        // The joined-layer action cluster: settings . share . leave (see [JoinedBottomActions] for why
        // each is shown when it is). Null everywhere else, so the create layer and the join gate keep
        // their own bottom edge.
        val bottomActions: (@Composable () -> Unit)? = if (joined && !reconfigureActive) {
            { JoinedBottomActions(membership, inviteUrl, overlays, actions) }
        } else {
            null
        }

        // The app-name nav label is always "SnapSync"; the joined event's name is the prominent heading.
        ScreenLayout(
            title = "SnapSync",
            heading = if (joined && !reconfigureActive) eventName else null,
            bottomActions = bottomActions,
            // Every join phase pins Cancel (and, on Ready, Join) as its own full-width bottom cluster; the
            // reconfigure surface likewise pins its own Save/Cancel — so both take the safe-area-anchored
            // bottom edge with no jump.
            contentPinsActionCluster = state is UiState.JoiningEvent || reconfigureActive,
            // Hidden, and only where there is a channel to send to.
            onTitleDoubleTap = actions.onSendDiagnostics?.let { { overlays.reportingBug = true } },
            // The rename pen, beside the heading — so only where a heading renders, which is the joined
            // layer outside the reconfigure surface. Unlike the hidden double-tap above, this is a real
            // control and appears in the accessibility tree. Not suppressed during a pending switch, for
            // the same reasons the settings gear is not (see the action cluster above): `RenameEvent`
            // guards the `eventId` itself, and suppressing here also hid the pen for the whole of a join's
            // own commit.
            onEditHeading =
                if (joined && !reconfigureActive && membership != null) {
                    { overlays.renaming = true }
                } else {
                    null
                },
        ) {
            if (reconfigureActive) {
                ReconfigureScreen(
                    membership = membership!!,
                    cutoff = cutoff,
                    shareableCount = actions.shareableCount,
                    photoPermission = photoPermission,
                    onSave = { eventId, direction, minPhotoDate, maxPhotoDate, saveToAlbum ->
                        overlays.reconfiguring = false
                        actions.onReconfigure(eventId, direction, minPhotoDate, maxPhotoDate, saveToAlbum)
                    },
                    onCancel = { overlays.reconfiguring = false },
                )
            } else when (state) {
                is UiState.CreateEvent ->
                    CreateEventScreen(state, actions.onCreateEvent, transientError, cutoff)
                UiState.CreatingEvent ->
                    CreatingEventScreen()
                is UiState.JoiningEvent ->
                    JoiningEventScreen(
                        state.phase, cutoff, actions.onConfirmJoin, actions.onAcknowledgeAccess,
                        actions.onCancelJoin, actions.onRetryLoad, actions.onRetryJoin,
                        actions.shareableCount, photoPermission,
                    )
                is UiState.Joined ->
                    JoinedLayer(
                        state.health, inviteUrl, actions.onRequestPermission, actions.onOpenSettings,
                        state.canChoosePhotos, actions.onChoosePhotos, state.ended, cutoff,
                    )
            }
        }

        // The overlays: confirm-leave, the rename sheet, the bug-report sheet, and the switch
        // confirmation. Extracted as one composable because they share exactly one thing — they sit ON
        // TOP of whatever the dispatcher above rendered — and because together they were more than half
        // of this function's statements.
        StatusOverlays(
            state = state,
            membership = membership,
            eventName = eventName,
            renameStatus = renameStatus,
            reconfigureActive = reconfigureActive,
            joined = joined,
            overlays = overlays,
            actions = actions,
        )
    }
}

/**
 * Everything that renders ON TOP of the current screen: the leave confirmation, the rename sheet, the
 * bug-report sheet, and the switch confirmation.
 *
 * They have nothing in common as features — they belong to four different capabilities — and that is
 * deliberate: what groups them is the only thing the layout cares about, which is that each is drawn
 * over whatever the dispatcher rendered rather than inside it. Keeping them together also keeps
 * [StatusScreen] readable as what it is, a dispatcher: state in, one surface out.
 */
@Composable
private fun StatusOverlays(
    state: UiState,
    membership: EventConfig?,
    eventName: String?,
    renameStatus: RenameStatus,
    reconfigureActive: Boolean,
    joined: Boolean,
    overlays: StatusOverlayState,
    actions: StatusActions,
) {
    if (overlays.confirmingLeave) {
        AppDestructiveConfirmDialog(
            title = "Leave this event?",
            body = "You'll stop sharing and receiving photos. Photos already in your " +
                "library stay.",
            confirmLabel = "Leave",
            cancelLabel = "Stay",
            onConfirm = {
                overlays.confirmingLeave = false
                actions.onLeaveEvent()
            },
            onDismiss = { overlays.confirmingLeave = false },
        )
    }

    // The bug-report sheet — the only moment this feature is ever visible, and therefore the only
    // place the operator learns what leaves the device. It names the payload rather than asking a
    // bare yes/no, and claims nothing about identifiers being removed (they are not: a report
    // travels verbatim, capability `diagnostic-logging`). Writing the description IS the
    // confirmation, so there is no second dialog behind Send. There is deliberately NO feedback
    // afterwards — the reporting SDK may queue and retransmit later, so "sent" is a claim the app
    // cannot honestly make.
    // The rename dialog (capability `event-rename`), opened by the pen beside the heading. Pre-filled
    // with the current name; the field is capped at the backend's own 100-character rule and confirm
    // is inert while the trimmed value is empty or unchanged, so a no-op rename never reaches the
    // network. A failure keeps the sheet open with the typed value and an error BANNER — never a
    // reddened field: a server saying no must not read as a complaint about the host's typing
    // (`event-creation-ui` makes the same call for the same reason).
    if (overlays.renaming && joined && membership != null) {
        // Success closes the sheet; either terminal value clears the latch, so the next rename starts
        // from a clean sequence rather than re-reading this one's outcome.
        LaunchedEffect(renameStatus) {
            when (renameStatus) {
                RenameStatus.Succeeded -> {
                    overlays.renaming = false
                    actions.onRenameStatusConsumed()
                }
                else -> Unit
            }
        }
        AppTextPromptSheet(
            title = "Rename event",
            body = "Everyone in the event sees the new name.",
            placeholder = "Event name",
            initialValue = membership.name,
            // The backend's own bound (capability `event-creation`), enforced by the input so an
            // over-long name is unreachable rather than rejected on a round trip.
            maxLength = 100,
            confirmLabel = "Save",
            cancelLabel = "Cancel",
            busy = renameStatus == RenameStatus.InFlight,
            error = (renameStatus as? RenameStatus.Failed)?.let { renameFailureText(it.reason) },
            // The id rides with the name so a switch landing mid-edit makes the use-case a no-op
            // rather than renaming a different event.
            onConfirm = { newName -> actions.onRenameEvent(membership.eventId, newName) },
            onDismiss = {
                overlays.renaming = false
                actions.onRenameStatusConsumed()
            },
        )
    }

    if (overlays.reportingBug && actions.onSendDiagnostics != null) {
        AppTextPromptSheet(
            title = "Report a problem",
            body = "Sent with the app's recent activity log and sync state to the developer's " +
                "error-tracking service.",
            placeholder = "What went wrong, and what were you doing?",
            // The description titles the report in the error-tracking service, so it is bounded to
            // stay readable in a list of issues (capability `diagnostic-logging`).
            maxLength = 200,
            confirmLabel = "Send",
            cancelLabel = "Cancel",
            onConfirm = { note ->
                overlays.reportingBug = false
                actions.onSendDiagnostics(note, screenLabel(state, reconfigureActive))
            },
            onDismiss = { overlays.reportingBug = false },
        )
    }

    // A switch confirmation over the joined screen (scanning a different event while joined).
    (state as? UiState.Joined)?.pendingSwitch?.let { switch ->
        SwitchDialog(
            switch = switch,
            currentEventName = eventName,
            onConfirmSwitch = actions.onConfirmSwitch,
            onCancelSwitch = actions.onCancelSwitch,
            onRetryLoad = actions.onRetryLoad,
        )
    }
}

        // The joined-layer action cluster: settings · share · leave. Settings needs a known membership;
        // sharing needs no photo access, and leave always shows. Hidden while the reconfigure surface is
        // open (it pins its own Save/Cancel). Loading and the create layer show none.
        //
        // Settings is deliberately NOT suppressed while a `pendingSwitch` is carried, though it once was.
        // The race that justified suppressing it — a reconfigure landing across a switch's config write —
        // is already prevented downstream by `ReconfigureEvent`'s own `eventId` guard (a surface opened for
        // a different membership persists nothing) and by the `LaunchedEffect(joined)` above, which closes
        // the surface the moment a config clears. It was inconsistent besides: share hands out an invite
        // URL from the same about-to-be-replaced config and leave ends the very membership a switch ends,
        // and neither was ever suppressed. And it cost more than it bought — a `pendingSwitch` is carried
        // for the whole of a JOIN's own commit too (the commit holds a pending join for the event being
        // joined, which is not a switch), so this gear vanished from the joined screen for as long as
        // provisioning took: the reported symptom in `SNAPSYNC-26`.
@Composable
private fun JoinedBottomActions(
    membership: EventConfig?,
    inviteUrl: String?,
    overlays: StatusOverlayState,
    actions: StatusActions,
) {
    if (membership != null) {
        SettingsButton(description = "Event settings", onClick = { overlays.reconfiguring = true })
    }
    if (inviteUrl != null) {
        ShareButton(description = "Share invite link", onClick = actions.onShareInvite)
    }
    LeaveButton(description = "Leave event", onClick = { overlays.confirmingLeave = true })
}

