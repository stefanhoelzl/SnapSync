package app.snapsync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import app.snapsync.model.EVENT_NAME_MAX_LENGTH
import app.snapsync.model.EventConfig
import app.snapsync.model.PermissionStatus
import app.snapsync.presentation.JoinedSurface
import app.snapsync.presentation.Layer
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.RenameState
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
import androidx.compose.foundation.layout.ColumnScope
import app.snapsync.ui.components.DialogCopy
import app.snapsync.ui.components.ScreenHeading
import app.snapsync.ui.components.PromptField


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

@Composable
fun StatusScreen(
    // Everything this screen renders. The membership, the invite URL, the inline create error and the
    // rename status all travel INSIDE it (capability `sync-status-screen`): a value the screen shows is a
    // value the state carries, so no call site can supply the state and silently omit a rendered value.
    state: UiState,
    // Bridges the cutoff picker (local wall-clock) to the UTC `…Z` cutoff string. Required — with NO
    // system-reading default (migration step 9): the host binds the `Clock`/`TimeZoneSource` ports
    // (production) or a fixed instant/zone (tests); this screen holds no clock or timezone knowledge.
    cutoff: CutoffFormatter,
    // The eighteen things this screen can ask for, bundled (see [StatusActions]). Defaulted, so a host
    // that wires none of them — the forge reviewing a forged state — constructs nothing.
    actions: StatusActions = StatusActions(),
) {
    AppTheme {
        // Derived once from the state. There is no screen-held visibility left to reset when the layer
        // changes: the container clears the overlays where a membership actually ends, and the reduction
        // masks a joined-only overlay against a layer that is not joined.
        val chrome = statusChrome(state)

        // The joined-layer action cluster: settings . share . leave (see [JoinedBottomActions] for why
        // each is shown when it is). Null everywhere else, so the create layer and the join gate keep
        // their own bottom edge.
        val bottomActions: (@Composable () -> Unit)? = if (chrome.showsJoinedChrome) {
            { JoinedBottomActions(actions) }
        } else {
            null
        }


        // The app-name nav label is always "SnapSync"; the joined event's name is the prominent heading.
        ScreenLayout(
            title = "SnapSync",
            // The rename pen rides with the heading it edits. Unlike the hidden double-tap below, it is a
            // real control and appears in the accessibility tree. Not suppressed during a pending switch,
            // for the same reasons the settings gear is not: `RenameEvent` guards the `eventId` itself,
            // and suppressing here also hid the pen for the whole of a join's own commit.
            heading = (state.layer as? Layer.Joined)?.membership?.name
                ?.takeIf { chrome.showsJoinedChrome }?.let {
                ScreenHeading(
                    text = it,
                    onEdit = if (chrome.canRename) actions.surfaces.onRenameOpen else null,
                    editDescription = "Rename event",
                )
            },
            bottomActions = bottomActions,
            contentPinsActionCluster = chrome.pinsActionCluster,
            // Hidden, and only where there is a channel to send to.
            onTitleDoubleTap = actions.onSendDiagnostics?.let { actions.surfaces.onReportBugOpen },
        ) {
            CurrentLayer(state = state, chrome = chrome, cutoff = cutoff, actions = actions)
        }
        // The overlays sit ON TOP of whatever layer rendered above.
        StatusOverlays(state = state, actions = actions)
    }
}

/**
 * What the status screen's own chrome shows, derived once.
 *
 * `joined && !reconfigureActive` was written out three separate times — the heading, the bottom action
 * cluster and the rename pen each re-derived it — and `membership != null` twice more on top. They are
 * one fact with three consequences: the joined layer is showing its OWN chrome, which the reconfigure
 * surface replaces wholesale. Three copies of a conjunction is three places to change and two places to
 * forget, and the failure would be silent — a heading that stays while its pen disappears.
 *
 * [reconfiguring] carries the membership rather than a flag, so it is non-null exactly while the
 * reconfigure surface shows. That turns an invariant the call site used to assert with `!!` and a comment
 * into one the compiler keeps.
 */
private class StatusChrome(
    val joined: Boolean,
    val reconfiguring: JoinedSurface.Reconfigure?,
    val membership: EventConfig?,
    val showsJoinedChrome: Boolean,
    val canRename: Boolean,
    val pinsActionCluster: Boolean,
)

/**
 * The reconfigure surface renders only while joined with a known membership; if the config drops (a leave
 * lands) the caller resets the flag so a later rejoin does not reopen it.
 */
private fun statusChrome(state: UiState): StatusChrome {
    // The membership comes off the state, which carries it non-null exactly when joined — so the
    // `membership != null` conjunctions this function used to carry have no expression left.
    val joinedLayer = state.layer as? Layer.Joined
    val membership = joinedLayer?.membership
    val joined = membership != null
    val reconfiguring = joinedLayer?.surface as? JoinedSurface.Reconfigure
    val showsJoinedChrome = joined && reconfiguring == null
    return StatusChrome(
        joined = joined,
        reconfiguring = reconfiguring,
        membership = membership,
        showsJoinedChrome = showsJoinedChrome,
        canRename = showsJoinedChrome,
        // Every join phase pins Cancel (and, on Ready, Join) as its own full-width bottom cluster; the
        // reconfigure surface likewise pins its own Save/Cancel — so both take the safe-area-anchored
        // bottom edge with no jump.
        pinsActionCluster = state.layer is Layer.JoiningEvent || reconfiguring != null,
    )
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
private fun StatusOverlays(state: UiState, actions: StatusActions) {
    val joined = state.layer as? Layer.Joined
    val overlays = state.overlays
    if (overlays.confirmingLeave) {
        LeaveConfirmDialog(actions)
    }
    if (overlays.renaming && joined != null) {
        RenameSheet(joined.membership, joined.renameState, actions)
    }
    if (overlays.reportingBug && actions.onSendDiagnostics != null) {
        BugReportSheet(actions, actions.onSendDiagnostics, screenLabel(state))
    }
    // A switch confirmation over the joined screen (scanning a different event while joined).
    joined?.pendingSwitch?.let { switch ->
        SwitchDialog(
            switch = switch,
            currentEventName = joined.membership.name,
            onConfirmSwitch = actions.switch.onConfirmSwitch,
            onCancelSwitch = actions.switch.onCancelSwitch,
            onRetryLoad = actions.join.onRetryLoad,
        )
    }
}

/** Leaving is destructive and irreversible from here, so it is confirmed rather than merely tapped. */
@Composable
private fun LeaveConfirmDialog(actions: StatusActions) {
    AppDestructiveConfirmDialog(
        copy = DialogCopy(
            title = "Leave this event?",
            body = "You'll stop sharing and receiving photos. Photos already in your " +
            "library stay.",
            confirmLabel = "Leave",
            cancelLabel = "Stay",
        ),
        onConfirm = actions.joined.onLeaveEvent,
        onDismiss = actions.surfaces.onConfirmLeaveDismiss,
    )
}

/**
 * The rename dialog (capability `event-rename`), opened by the pen beside the heading.
 *
 * Pre-filled with the current name; the field is capped at the backend's own 100-character rule and
 * confirm is inert while the trimmed value is empty or unchanged, so a no-op rename never reaches the
 * network. A failure keeps the sheet open with the typed value and an error BANNER — never a reddened
 * field: a server saying no must not read as a complaint about the host's typing (`event-creation-ui`
 * makes the same call for the same reason).
 */
@Composable
private fun RenameSheet(
    membership: EventConfig,
    renameState: RenameState,
    actions: StatusActions,
) {
    // Success closes the sheet; either terminal value clears the latch, so the next rename starts
    // from a clean sequence rather than re-reading this one's outcome.
    LaunchedEffect(renameState) {
        when (renameState) {
            RenameState.Succeeded -> {
                actions.surfaces.onRenameDismiss()
                actions.joined.onRenameStatusConsumed()
            }
            else -> Unit
        }
    }
    AppTextPromptSheet(
        copy = DialogCopy(
            title = "Rename event",
            body = "Everyone in the event sees the new name.",
            confirmLabel = "Save",
            cancelLabel = "Cancel",
        ),
        field = PromptField(
            placeholder = "Event name",
            initialValue = membership.name,
            // The backend's own bound (capability `event-creation`), enforced by the input so an
            // over-long name is unreachable rather than rejected on a round trip. The SAME constant the
            // create form caps at — it was a bare literal here, which is how the two could have drifted.
            maxLength = EVENT_NAME_MAX_LENGTH,
            busy = renameState == RenameState.InFlight,
            // The copy arrives already formatted: turning a failure REASON into words is the reduction's
            // job, exactly as it is for the create layer's twin (capability `event-rename`).
            error = (renameState as? RenameState.Failed)?.message,
        ),
        // The id rides with the name so a switch landing mid-edit makes the use-case a no-op
        // rather than renaming a different event.
        onConfirm = { newName -> actions.joined.onRenameEvent(membership.eventId, newName) },
        onDismiss = {
            actions.surfaces.onRenameDismiss()
            actions.joined.onRenameStatusConsumed()
        },
    )
}

/**
 * The bug-report sheet — the only moment this feature is ever visible, and therefore the only place the
 * operator learns what leaves the device.
 *
 * It names the payload rather than asking a bare yes/no, and claims nothing about identifiers being
 * removed (they are not: a report travels verbatim, capability `diagnostic-logging`). Writing the
 * description IS the confirmation, so there is no second dialog behind Send. There is deliberately NO
 * feedback afterwards — the reporting SDK may queue and retransmit later, so "sent" is a claim the app
 * cannot honestly make.
 *
 * This prose used to sit above the RENAME block, stranded there by a reordering; giving each overlay its
 * own function is what makes that misplacement unrepresentable.
 */
@Composable
private fun BugReportSheet(
    actions: StatusActions,
    onSend: (note: String, screen: String) -> Unit,
    screen: String,
) {
    AppTextPromptSheet(
        copy = DialogCopy(
            title = "Report a problem",
            body = "Sent with the app's recent activity log and sync state to the developer's " +
            "error-tracking service.",
            confirmLabel = "Send",
            cancelLabel = "Cancel",
        ),
        field = PromptField(
            placeholder = "What went wrong, and what were you doing?",
            // The description titles the report in the error-tracking service, so it is bounded to
            // stay readable in a list of issues (capability `diagnostic-logging`).
            maxLength = 200,
        ),
        onConfirm = { note ->
            actions.surfaces.onReportBugDismiss()
            onSend(note, screen)
        },
        onDismiss = actions.surfaces.onReportBugDismiss,
    )
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
private fun JoinedBottomActions(actions: StatusActions) {
    // Settings and share used to be guarded on a nullable membership and a nullable invite URL. The
    // joined state carries both non-null, so the guards have nothing left to test: reaching this cluster
    // IS having a membership, and the invite URL is derived from it.
    SettingsButton(description = "Event settings", onClick = actions.surfaces.onOpenReconfigure)
    ShareButton(description = "Share invite link", onClick = actions.joined.onShareInvite)
    LeaveButton(description = "Leave event", onClick = actions.surfaces.onConfirmLeaveOpen)
}

/**
 * Which layer the app is showing: the reconfigure surface if it is open, else the one the [state] names.
 *
 * Its own function because it is the app's ONE navigation decision, and [StatusScreen] around it does
 * something different — it owns the screen's chrome (heading, bottom cluster, the two title gestures) and
 * the overlay flags. Reading "what is on screen right now" meant reading past all of that.
 *
 * The reconfigure surface takes precedence over the state's own layer rather than sitting beside it: it
 * is a modal edit of the joined layer, and [reconfiguring] is non-null exactly while it shows.
 */
@Composable
private fun ColumnScope.CurrentLayer(
    state: UiState,
    chrome: StatusChrome,
    // Still needed by the CREATE form (its own local name/date state, which this change does not lift)
    // and by the joined layer's clock line. The RANGE form no longer needs it: its bounds arrive
    // resolved (capability `sync-status-screen`).
    cutoff: CutoffFormatter,
    actions: StatusActions,
) {
    val reconfiguring = chrome.reconfiguring
    if (reconfiguring != null && chrome.membership != null) {
        ReconfigureScreen(
            membership = chrome.membership,
            surface = reconfiguring,
            participation = actions.participation,
            photoPermission = actions.participation.photoPermission,
            onSave = actions.joined.onReconfigure,
            onCancel = actions.surfaces.onCancelReconfigure,
        )
    } else when (val layer = state.layer) {
        is Layer.UpdateRequired ->
            UpdateRequiredScreen(layer, actions.onOpenLink)
        is Layer.CreateEvent ->
            CreateEventScreen(layer, actions.onCreateEvent, cutoff)
        Layer.CreatingEvent ->
            CreatingEventScreen()
        is Layer.JoiningEvent ->
            JoiningEventScreen(
                layer = layer,
                actions = JoinActions(
                    onConfirm = actions.join.onConfirmJoin,
                    onRetryJoin = actions.join.onRetryJoin,
                    onAcknowledgeAccess = actions.join.onAcknowledgeAccess,
                    onCancel = actions.join.onCancelJoin,
                    onRetryLoad = actions.join.onRetryLoad,
                    participation = actions.participation,
                ),
                photoPermission = actions.participation.photoPermission,
            )
        is Layer.Joined ->
            JoinedLayer(layer, actions.access, cutoff)
    }
}
