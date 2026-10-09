package app.snapsync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.snapsync.model.AppLink
import app.snapsync.model.BuildLabel
import app.snapsync.model.DateFormats
import app.snapsync.model.EVENT_NAME_MAX_LENGTH
import app.snapsync.model.EventConfig
import app.snapsync.model.JoinedSurface
import app.snapsync.model.Layer
import app.snapsync.model.MobileDataState
import app.snapsync.model.RenameState
import app.snapsync.model.ReportDestination
import app.snapsync.model.ReportOutcome
import app.snapsync.model.ScreenMessage
import app.snapsync.model.UiState
import app.snapsync.model.offersMenu
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.ui.components.AppDestructiveConfirmDialog
import app.snapsync.ui.components.AppFooterTextActions
import app.snapsync.ui.components.AppMenuDivider
import app.snapsync.ui.components.AppMenuDrawer
import app.snapsync.ui.components.AppMenuFooter
import app.snapsync.ui.components.AppMenuHeader
import app.snapsync.ui.components.AppMenuIcon
import app.snapsync.ui.components.AppMenuItem
import app.snapsync.ui.components.AppMenuSwitch
import app.snapsync.ui.components.AppNotice
import app.snapsync.ui.components.AppQrSheet
import app.snapsync.ui.components.AppTextPromptSheet
import app.snapsync.ui.components.AppTheme
import app.snapsync.ui.components.DialogCopy
import app.snapsync.ui.components.LeaveTextAction
import app.snapsync.ui.components.PromptField
import app.snapsync.ui.components.QrTextAction
import app.snapsync.ui.components.ScreenHeading
import app.snapsync.ui.components.ScreenLayout
import app.snapsync.ui.components.SettingsTextAction
import app.snapsync.ui.components.ShareTextAction
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.app_name
import app.snapsync.ui.resources.cancel
import app.snapsync.ui.resources.event_name_placeholder
import app.snapsync.ui.resources.footer_settings
import app.snapsync.ui.resources.invite_caption
import app.snapsync.ui.resources.leave_body
import app.snapsync.ui.resources.leave_cancel
import app.snapsync.ui.resources.leave_confirm
import app.snapsync.ui.resources.leave_event
import app.snapsync.ui.resources.leave_title
import app.snapsync.ui.resources.menu_privacy
import app.snapsync.ui.resources.menu_version
import app.snapsync.ui.resources.menu_website
import app.snapsync.ui.resources.mobile_data_not_saved
import app.snapsync.ui.resources.mobile_data_off_note
import app.snapsync.ui.resources.mobile_data_on_note
import app.snapsync.ui.resources.mobile_data_toggle
import app.snapsync.ui.resources.qr_sheet_title
import app.snapsync.ui.resources.rename_body
import app.snapsync.ui.resources.rename_event
import app.snapsync.ui.resources.report_not_sent
import app.snapsync.ui.resources.report_placeholder
import app.snapsync.ui.resources.report_problem
import app.snapsync.ui.resources.report_saved
import app.snapsync.ui.resources.report_sent
import app.snapsync.ui.resources.save
import app.snapsync.ui.resources.share_invite
import app.snapsync.ui.resources.show_qr
import org.jetbrains.compose.resources.stringResource

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
 * `UiState` or in the reduction: it is local Compose navigation, and the report and rename sheets
 * make the same call.
 *
 * A holder rather than four separate `var`s because the overlays that read them are a composable of
 * their own: four flags would otherwise cross that boundary as four values and four setters.
 */

@Composable
fun StatusScreen(
    // Everything this screen renders. The membership, the invite URL, the inline create error and the
    // rename status all travel INSIDE it: a value the screen shows is a
    // value the state carries, so no call site can supply the state and silently omit a rendered value.
    state: UiState,
    // Bridges the cutoff picker (local wall-clock) to the UTC `…Z` cutoff string. Required — with NO
    // system-reading default (migration step 9): the host binds the `Clock` port
    // (production) or a fixed instant/zone (tests); this screen holds no clock or timezone knowledge.
    cutoff: CutoffFormatter,
    // How a date reads — the platform's (the `DateFormatting` port's `formats`), asked in the language the strings
    // resolved to. Required, with no default: the screen knows no platform's CLDR data.
    dateFormats: (languageTag: String?) -> DateFormats,
    // Everything this screen can ask for, bundled (see [StatusActions]). Required: every host builds it
    // through the one factory, `statusActions(dispatch)`, so a forgotten action is a compile error.
    actions: StatusActions,
) {
    AppTheme(dateFormats) {
        // Derived once from the state. There is no screen-held visibility left to reset when the layer
        // changes: the container clears the overlays where a membership actually ends, and the reduction
        // masks a joined-only overlay against a layer that is not joined.
        val chrome = statusChrome(state)

        // The joined layer's docked footer: the invite pair, then settings and leave (see [JoinedFooter] for why
        // each is shown when it is). Null everywhere else, so the create layer and the join gate keep their own
        // bottom edge.
        val bottomActions: (@Composable ColumnScope.() -> Unit)? = if (chrome.showsJoinedChrome) {
            { JoinedFooter(actions, closed = chrome.closed, invitable = chrome.invitable) }
        } else {
            null
        }

        // The app menu is drawn over the whole screen; where the layer withholds it, the
        // title row draws no button and the reduction keeps the drawer shut.
        AppMenuDrawer(
            open = state.overlays.menuOpen,
            onDismiss = actions.menu.onMenuDismiss,
            menu = { AppMenu(state.build, state.mobileData, actions.menu) },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // The app-name nav label is always "SnapSync"; the joined event's name is the prominent heading.
                ScreenLayout(
                    title = stringResource(Res.string.app_name),
                    // The rename pen rides with the heading it edits. Unlike the hidden double-tap below, it is a
                    // real control and appears in the accessibility tree. Not suppressed during a pending switch,
                    // for the same reasons the settings gear is not: `RenameEvent` guards the `eventId` itself,
                    // and suppressing here also hid the pen for the whole of a join's own commit.
                    // Beneath it, that this device has joined and the event's dates.
                    heading = (state.layer as? Layer.Joined)?.let { joined ->
                        ScreenHeading(
                            text = joined.membership.name,
                            onEdit = if (chrome.canRename) actions.surfaces.onRenameOpen else null,
                            editDescription = stringResource(Res.string.rename_event),
                            details = { JoinedHeadingDetails(joined, cutoff) },
                        )
                    },
                    bottomActions = bottomActions,
                    contentPinsActionCluster = chrome.pinsActionCluster,
                    // The hidden second way to the report sheet; the menu's "Report a problem" is the visible one.
                    onTitleDoubleTap = actions.surfaces.onReportBugOpen,
                    onMenu = actions.menu.onMenuOpen.takeIf { state.layer.offersMenu },
                ) {
                    CurrentLayer(state = state, cutoff = cutoff, actions = actions)
                }
                state.overlays.reportNotice?.let { outcome ->
                    AppNotice(
                        text = reportNoticeText(outcome),
                        onDismiss = actions.menu.onReportNoticeDismiss,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        // The overlays sit ON TOP of whatever layer rendered above.
        StatusOverlays(state = state, actions = actions)
    }
}

/**
 * The app menu's rows: the device's mobile-data switch set apart at the top, then the report, then the site's
 * pages, then which build this is — shown, never a control.
 */
@Composable
private fun ColumnScope.AppMenu(build: BuildLabel, mobileData: MobileDataState, actions: MenuActions) {
    AppMenuHeader(stringResource(Res.string.app_name), onClose = actions.onMenuDismiss)
    // Everything else the app does keeps working on any network, which is why the note speaks of photos only.
    AppMenuSwitch(
        icon = AppMenuIcon.MOBILE_DATA,
        label = stringResource(Res.string.mobile_data_toggle),
        note = stringResource(if (mobileData.on) Res.string.mobile_data_on_note else Res.string.mobile_data_off_note),
        checked = mobileData.on,
        onCheckedChange = actions.onMobileData,
        error = if (mobileData.notSaved) stringResource(Res.string.mobile_data_not_saved) else null,
    )
    AppMenuDivider()
    AppMenuItem(
        icon = AppMenuIcon.REPORT,
        label = stringResource(Res.string.report_problem),
        onClick = actions.onReportBug,
    )
    AppMenuDivider()
    AppMenuItem(
        icon = AppMenuIcon.WEBSITE,
        label = stringResource(Res.string.menu_website),
        onClick = {
            actions.onOpenLink(AppLink.WEBSITE)
        },
    )
    AppMenuItem(
        icon = AppMenuIcon.PRIVACY,
        label = stringResource(Res.string.menu_privacy),
        onClick = {
            actions.onOpenLink(AppLink.PRIVACY_POLICY)
        },
    )
    AppMenuFooter(stringResource(Res.string.menu_version, build.version, build.buildNumber))
}

/**
 * The word on a confirmed report: what became of it, and never more — "sent" is a
 * hand-off, so it claims no delivery, and the failure is told calmly.
 */
@Composable
internal fun reportNoticeText(outcome: ReportOutcome): String = stringResource(
    when (outcome) {
        ReportOutcome.SENT -> Res.string.report_sent
        ReportOutcome.SAVED -> Res.string.report_saved
        ReportOutcome.NOT_SENT -> Res.string.report_not_sent
    },
)

/**
 * What the status screen's own chrome shows, derived once: whether the joined layer's heading, pen and docked
 * footer show — always while joined, since the event's settings open in a sheet OVER the joined screen rather than
 * replacing it — and which bottom edge the layer takes.
 */
private class StatusChrome(
    val showsJoinedChrome: Boolean,
    val canRename: Boolean,
    val pinsActionCluster: Boolean,
    /** The joined event has closed: only Leave remains. */
    val closed: Boolean,
    /** There is a whole invite to offer — not for an encrypted event whose key cannot be read. */
    val invitable: Boolean,
)

private fun statusChrome(state: UiState): StatusChrome {
    val joinedLayer = state.layer as? Layer.Joined
    val showsJoinedChrome = joinedLayer != null
    return StatusChrome(
        showsJoinedChrome = showsJoinedChrome,
        canRename = joinedLayer != null && !joinedLayer.closed,
        closed = joinedLayer?.closed == true,
        invitable = joinedLayer?.inviteUrl != null,
        // Every join phase pins Cancel (and, on Ready, Join) as its own full-width bottom cluster; the create form
        // its Create + hint (the in-flight create screen too, so the swap does not jump), and the joined screen docks
        // its invite and membership actions — so all take the safe-area-anchored bottom edge.
        pinsActionCluster = showsJoinedChrome || when (state.layer) {
            is Layer.JoiningEvent, is Layer.CreateEvent, Layer.CreatingEvent -> true
            else -> false
        },
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
    // The event's settings, in a sheet over the joined screen. First, so a
    // dialog raised while they are open (a switch, a report) draws above them.
    (joined?.surface as? JoinedSurface.Reconfigure)?.let { settings ->
        ReconfigureSheet(
            surface = settings,
            participation = actions.participation,
            withdrawal = actions.joined.withdrawal,
            onClose = actions.surfaces.onCancelReconfigure,
        )
    }
    if (overlays.confirmingLeave) {
        LeaveConfirmDialog(actions)
    }
    if (overlays.renaming && joined != null) {
        RenameSheet(joined.membership, joined.renameState, actions)
    }
    val qrInvite = joined?.inviteUrl
    if (overlays.showingQr && qrInvite != null) {
        AppQrSheet(
            title = stringResource(Res.string.qr_sheet_title, joined.membership.name),
            content = qrInvite,
            caption = stringResource(Res.string.invite_caption),
            onDismiss = actions.joined.onQrDismiss,
        )
    }
    if (overlays.reportingBug) {
        BugReportSheet(
            actions,
            actions.menu.onSendDiagnostics,
            screenLabel(state),
            state.reportDestination,
            overlays.reportSeed,
        )
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
            title = stringResource(Res.string.leave_title),
            body = stringResource(Res.string.leave_body),
            confirmLabel = stringResource(Res.string.leave_confirm),
            cancelLabel = stringResource(Res.string.leave_cancel),
        ),
        onConfirm = actions.joined.onLeaveEvent,
        onDismiss = actions.surfaces.onConfirmLeaveDismiss,
    )
}

/**
 * The rename dialog, opened by the pen beside the heading.
 *
 * Pre-filled with the current name; the field is capped at the backend's own 100-character rule and
 * confirm is inert while the trimmed value is empty or unchanged, so a no-op rename never reaches the
 * network. A failure keeps the sheet open with the typed value and an error BANNER — never a reddened
 * field: a server saying no must not read as a complaint about the host's typing (the create
 * form makes the same call for the same reason).
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
            title = stringResource(Res.string.rename_event),
            body = stringResource(Res.string.rename_body),
            confirmLabel = stringResource(Res.string.save),
            cancelLabel = stringResource(Res.string.cancel),
        ),
        field = PromptField(
            placeholder = stringResource(Res.string.event_name_placeholder),
            initialValue = membership.name,
            // The backend's own bound, enforced by the input so an
            // over-long name is unreachable rather than rejected on a round trip. The SAME constant the
            // create form caps at — it was a bare literal here, which is how the two could have drifted.
            maxLength = EVENT_NAME_MAX_LENGTH,
            busy = renameState == RenameState.InFlight,
            // The reduction names the failure; the words are this screen's, as for the create layer's twin.
            error = (renameState as? RenameState.Failed)?.message?.text(),
        ),
        // The id rides with the name so a switch landing mid-edit makes the use-case a no-op
        // rather than renaming a different event.
        onConfirm = { newName ->
            actions.joined.onRenameEvent(membership.eventId, newName)
        },
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
 * removed (they are not: a report travels verbatim). Writing the
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
    destination: ReportDestination,
    seed: ScreenMessage?,
) {
    AppTextPromptSheet(
        copy = reportCopy(destination).let { words ->
            DialogCopy(
                title = stringResource(Res.string.report_problem),
                body = stringResource(words.body),
                confirmLabel = stringResource(words.confirm),
                cancelLabel = stringResource(Res.string.cancel),
            )
        },
        field = PromptField(
            placeholder = stringResource(Res.string.report_placeholder),
            // The description titles the report in the error-tracking service, so it is bounded to
            // stay readable in a list of issues.
            maxLength = 200,
            // A report the app offered opens with its description written, which may be sent as it stands.
            initialValue = seed?.reportSeed().orEmpty(),
            submitUnchanged = seed != null,
        ),
        onConfirm = { note ->
            actions.surfaces.onReportBugDismiss()
            onSend(note, screen)
        },
        onDismiss = actions.surfaces.onReportBugDismiss,
    )
}

/**
 * The joined layer's docked footer: the two equal ways to
 * invite — share the link, show its QR code — then, set apart by a line, the quiet text actions for the
 * event's settings and for leaving. A CLOSED event admits nobody and changes nothing any more, so it offers
 * only Leave; an encrypted event whose key this device cannot read offers no invite.
 *
 * Settings is deliberately NOT suppressed while a `pendingSwitch` is carried, though it once was: the race that
 * justified it is prevented downstream by `ReconfigureEvent`'s own `eventId` guard, and a `pendingSwitch` is
 * carried for the whole of a JOIN's own commit too, so the action vanished for as long as provisioning took —
 * the reported symptom in `SNAPSYNC-26`.
 */
@Composable
private fun JoinedFooter(actions: StatusActions, closed: Boolean, invitable: Boolean) {
    // An encrypted event's invite is offered only whole: no key read, no invite.
    if (!closed && invitable) {
        AppFooterTextActions {
            ShareTextAction(label = stringResource(Res.string.share_invite), onClick = actions.joined.onShareInvite)
            QrTextAction(label = stringResource(Res.string.show_qr), onClick = actions.joined.onQrOpen)
        }
    }
    AppFooterTextActions {
        if (!closed) {
            SettingsTextAction(
                label = stringResource(Res.string.footer_settings),
                onClick = actions.surfaces.onOpenReconfigure,
            )
        }
        LeaveTextAction(label = stringResource(Res.string.leave_event), onClick = actions.surfaces.onConfirmLeaveOpen)
    }
}

/**
 * Which layer the app is showing: the one the [state] names. The event's settings are not a layer — they open in a
 * sheet over the joined one, drawn with the other overlays.
 *
 * Its own function because it is the app's ONE navigation decision, and [StatusScreen] around it does
 * something different — it owns the screen's chrome (heading, bottom cluster, the two title gestures) and
 * the overlay flags. Reading "what is on screen right now" meant reading past all of that.
 */
@Composable
private fun ColumnScope.CurrentLayer(
    state: UiState,
    // Needed by the CREATE form (its own name/date draft, held by `CreateFlow`) and by the joined layer's
    // explanation, which names the member's shared range. The RANGE form no longer needs it: its bounds arrive
    // resolved.
    cutoff: CutoffFormatter,
    actions: StatusActions,
) {
    when (val layer = state.layer) {
        is Layer.UpdateRequired ->
            UpdateRequiredScreen(layer, actions.onOpenLink)
        // ONE branch for both create layers, so the form's draft survives a failed create's round trip
        // through the in-flight screen — see [CreateFlow].
        is Layer.CreateEvent, Layer.CreatingEvent ->
            CreateFlow(
                layer,
                actions.onCreateEvent,
                actions.access.onOpenSettings,
                cutoff,
                actions.surfaces.onReportRefusal,
            )
        is Layer.JoiningEvent ->
            JoiningEventScreen(
                layer = layer,
                actions = JoinActions(
                    onConfirm = actions.join.onConfirmJoin,
                    onRetryJoin = actions.join.onRetryJoin,
                    onCancel = actions.join.onCancelJoin,
                    onRetryLoad = actions.join.onRetryLoad,
                    participation = actions.participation,
                    onOpenSettings = actions.access.onOpenSettings,
                    onReportRefusal = actions.surfaces.onReportRefusal,
                ),
            )
        is Layer.Joined ->
            JoinedLayer(layer, cutoff, actions.access, onOpenEventSettings = actions.surfaces.onOpenReconfigure)
    }
}
