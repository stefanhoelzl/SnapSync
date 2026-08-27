package app.snapsync.ui

import app.snapsync.model.EventStart
import app.snapsync.model.EventEnd
import app.snapsync.model.DeletesAt
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureCeiling
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.model.Direction
import app.snapsync.model.PermissionStatus
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.JoinPhase
import app.snapsync.ui.components.AppAccessPoint
import app.snapsync.ui.components.AppInvitationHeaderLoading
import app.snapsync.ui.components.AppJoinProgress
import app.snapsync.ui.components.AppNoticeCard
import app.snapsync.ui.components.AppSummaryCard
import app.snapsync.ui.components.JOIN_HERO_SUBTITLE
import app.snapsync.ui.components.JoinAccessChoose
import app.snapsync.ui.components.JoinAccessCutoff
import app.snapsync.ui.components.JoinAccessLibrary
import app.snapsync.ui.components.JoinAccessShare
import app.snapsync.ui.components.JoinNoticeFailed
import app.snapsync.ui.components.JoinNoticeInvalid
import app.snapsync.ui.components.JoinNoticeOffline
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import app.snapsync.ui.components.AppRangePresetChoices
import app.snapsync.ui.components.FromChoice
import app.snapsync.ui.components.UntilChoice
import app.snapsync.ui.components.AppEventHeaderCompact
import app.snapsync.ui.components.AppSectionNote
import app.snapsync.ui.components.AppMinorSection
import app.snapsync.ui.components.AppSectionValue
import app.snapsync.ui.components.AppSummaryToggle
import app.snapsync.ui.components.AppToggleSection
import app.snapsync.ui.components.appDateLabel
import app.snapsync.ui.components.appDateTimeLabel
import app.snapsync.ui.components.PrimaryButton
import app.snapsync.ui.components.SecondaryButton
import app.snapsync.ui.components.StatusHint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.snapsync.ui.components.RangeChoiceActions
import app.snapsync.ui.components.RangeChoices

// The join gate (capability `join-event`): the full-screen surface a scanned link opens, and the
// status-plus-actions phases it dispatches over. The Ready decision surface lives in
// `JoinReadySurface.kt`, the phase-window accessors with the derivation that reads them in
// `JoinSelection.kt`.


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
internal fun JoiningEventScreen(
    phase: JoinPhase,
    cutoff: CutoffFormatter,
    onConfirm: (CaptureCutoff, CaptureCeiling, Direction, Boolean) -> Unit,
    onAcknowledgeAccess: () -> Unit,
    onCancel: () -> Unit,
    onRetryLoad: () -> Unit,
    onRetryJoin: (CaptureCutoff, CaptureCeiling, Direction, Boolean) -> Unit,
    shareableCount: suspend (cutoff: CaptureCutoff, until: CaptureCeiling?) -> Int?,
    photoPermission: PermissionStatus,
) {
    // The two participation switches, both default ON. Direction is DERIVED from them, never chosen:
    // share+receive → Both, share only → UploadOnly, receive only → DownloadOnly. There is deliberately no
    // "no photos" option in either — "not sharing" IS the share switch off, "not receiving" the receive
    // switch off. Both off is representable and does nothing; Join is disabled with a stated reason rather
    // than one switch silently flipping the other.
    var shareOn by remember { mutableStateOf(true) }
    var receiveOn by remember { mutableStateOf(true) }

    // The capture-date RANGE is a pair of presets (capability `photo-selection-policy`), defaulting to the
    // FULL event window `[Event start, Event end]` (narrow, never widen — admits on doubt). What is
    // REMEMBERED is the presets and (for Custom) the picked wall-clock values, NEVER a default instant — the
    // instants are derived fresh from the phase on every composition, sidestepping the seeding bug a
    // `remember`-ed instant had (this screen mounts at `Loading`, before the details fetch, so a
    // first-composition seed captured `now` and never re-ran).
    var fromPreset by remember { mutableStateOf(FromChoice.EVENT_START) }
    var fromCustom by remember { mutableStateOf<LocalDateTime?>(null) }
    var untilPreset by remember { mutableStateOf(UntilChoice.EVENT_END) }
    var untilCustom by remember { mutableStateOf<LocalDateTime?>(null) }
    var chosenSaveToAlbum by remember { mutableStateOf(false) }

    // Range derivation from the phase's window. On Ready the host guarantees both `startsAt` and `endsAt`;
    // a screen mounted straight into a phase with neither falls back to a now
    // from-bound and an effectively-unbounded until (the safe direction), which is inert there — those
    // phases render no range row, and a retry re-sends the range the Ready phase already committed.
    // What the guest has actually chosen, resolved from the phase's window and the four choice controls
    // (see [JoinSelection]). Pulled out because it is pure derivation and answers a different question
    // from everything below it: this decides WHAT would be committed, the `when` further down decides
    // what is DRAWN.
    val selection = rememberJoinSelection(
        phase = phase,
        cutoff = cutoff,
        fromPreset = fromPreset,
        fromCustom = fromCustom,
        untilPreset = untilPreset,
        untilCustom = untilCustom,
        shareOn = shareOn,
        receiveOn = receiveOn,
    )

    // ONE dispatcher over the phase, and every branch names a composable that renders BOTH that
    // phase's body and the actions it offers. It was two `when`s ninety lines apart plus an early
    // return, so a phase's appearance and its buttons were stated in three different places and
    // nothing but exhaustiveness kept them in step.
    when (phase) {
        // The one phase that is a *decision surface* rather than a status-plus-actions surface, so it
        // owns its whole layout instead of the scaffold every other phase opts into. That is why it
        // is an ordinary branch here: once the shape is something a phase CHOOSES, a phase that wants
        // a different one simply calls something else, and no early return has to jump the queue.
        is JoinPhase.Ready -> ReadyLayout(
            state = ReadyState(
                eventName = phase.name,
                selection = selection,
                choices = RangeChoices(fromPreset, fromCustom, untilPreset, untilCustom),
                labels = ReadyLabels(
                    range = cutoff.formatRange(selection.fromResolved, selection.untilResolved),
                    floor = appDateTimeLabel(selection.windowStart),
                    ceiling = appDateTimeLabel(selection.windowEnd),
                    // The retention deadline, rendered as a plain date. Absent only if a phase somehow
                    // lost it, in which case the section states the fixed ceiling alone rather than
                    // inventing a date.
                    deletes = phase.deletesAt()?.let { cutoff.toLocal(it.at) }?.let(::appDateLabel),
                ),
                shareOn = shareOn,
                receiveOn = receiveOn,
                saveToAlbum = chosenSaveToAlbum,
                photoPermission = photoPermission,
            ),
            actions = ReadyActions(
                choices = RangeChoiceActions(
                    onFromPreset = { fromPreset = it },
                    onFromCustom = { fromCustom = it },
                    onUntilPreset = { untilPreset = it },
                    onUntilCustom = { untilCustom = it },
                ),
                onShareOn = { shareOn = it },
                onReceiveOn = { receiveOn = it },
                onSaveToAlbum = { chosenSaveToAlbum = it },
                onJoin = {
                    onConfirm(
                        selection.chosenFrom,
                        selection.chosenUntil,
                        selection.chosenDirection,
                        chosenSaveToAlbum,
                    )
                },
                onCancel = onCancel,
                shareableCount = shareableCount,
            ),
        )
        JoinPhase.Loading -> LoadingPhase()
        is JoinPhase.ExplainAccess -> ExplainAccessPhase(
            name = phase.name,
            onAcknowledge = onAcknowledgeAccess,
            onCancel = onCancel,
        )
        JoinPhase.NotFound -> NotFoundPhase(onCancel = onCancel)
        JoinPhase.LoadFailed -> LoadFailedPhase(onRetry = onRetryLoad, onCancel = onCancel)
        is JoinPhase.Committing -> CommittingPhase(name = phase.name)
        is JoinPhase.CommitFailed -> CommitFailedPhase(
            name = phase.name,
            onRetry = {
                onRetryJoin(
                    selection.chosenFrom,
                    selection.chosenUntil,
                    selection.chosenDirection,
                    chosenSaveToAlbum,
                )
            },
            onCancel = onCancel,
        )
    }
}

/**
 * The shape every phase but **Ready** takes: a body filling the available height, and the actions that
 * phase offers pinned beneath it. A phase OPTS INTO this — it is not imposed on the screen — which is
 * what lets `Ready` decline it without needing an early return to escape.
 *
 * The default empty [actions] is the in-flight statement: a phase that offers no actions says so by
 * leaving the slot out, rather than by appearing in a second `when` under an `-> Unit` branch.
 */
@Composable
private fun PhaseScaffold(
    body: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).fillMaxWidth(), content = body)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = actions,
        )
    }
}

/**
 * Optimistic loading: the invitation hero with the name still a placeholder, and a calm spinner filling
 * the space below. Resolves into ExplainAccess/Ready with no header jump — the badge and eyebrow never
 * move across Loading -> ExplainAccess -> Ready -> Committing, only the name resolves.
 */
@Composable
private fun LoadingPhase() = PhaseScaffold(
    body = {
        AppInvitationHeaderLoading(subtitle = JOIN_HERO_SUBTITLE)
        CenteredBody { AppJoinProgress("Loading event details …") }
    },
)

/**
 * The photo-access explainer, ahead of the confirm and ahead of the system dialog (capability
 * `join-event`). It names the event it is inviting you to (the hero) and states the consent facts as a
 * scannable card, top-anchored beneath it: share-first (the automatic sharing is the half that deserves
 * informed consent, so it leads), then that full access is genuinely needed for BOTH halves, then that
 * limited ("pick which photos") is a first-class choice (capability `limited-photo-access`, not a
 * degraded one), then the cutoff.
 *
 * "I understand" is the ONLY path from the join gate to the system dialog (CTA-only priming). Cancel is
 * the same cancel every other phase pins — it abandons the join.
 */
@Composable
private fun ExplainAccessPhase(
    name: String,
    onAcknowledge: () -> Unit,
    onCancel: () -> Unit,
) = PhaseScaffold(
    body = {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppEventHeaderCompact(title = name, subtitle = JOIN_HERO_SUBTITLE)
            AppSummaryCard(title = "What joining does") {
                AppAccessPoint(
                    icon = JoinAccessShare,
                    title = "Your photos are shared automatically",
                    body = "The photos you take show up for everyone in the event.",
                    divider = false,
                )
                AppAccessPoint(
                    icon = JoinAccessLibrary,
                    title = "SnapSync needs your photo library",
                    body = "To share yours, and to save the photos other members send you.",
                )
                AppAccessPoint(
                    icon = JoinAccessChoose,
                    title = "Allow all photos, or pick which to share",
                    body = "Choosing specific photos works too — and you can add more anytime.",
                )
                AppAccessPoint(
                    icon = JoinAccessCutoff,
                    title = "Only photos after the date you choose",
                    body = "You pick that date on the next screen — nothing older is shared.",
                )
            }
        }
    },
    actions = {
        PrimaryButton(label = "I understand", onClick = onAcknowledge)
        SecondaryButton(label = "Cancel", onClick = onCancel)
    },
)

/**
 * A dead end — the event does not exist, so no invitation hero, just an honest notice. There is nothing
 * to be invited to, so this never shows a false invitation; Cancel is the only way out.
 */
@Composable
private fun NotFoundPhase(onCancel: () -> Unit) = PhaseScaffold(
    body = {
        CenteredBody {
            AppNoticeCard(
                icon = JoinNoticeInvalid,
                title = "Invalid invite",
                body = "This invite is invalid or the event no longer exists.",
            )
        }
    },
    actions = { SecondaryButton(label = "Cancel", onClick = onCancel) },
)

/**
 * Transient — the event may well exist; the fetch just failed, so this one is retryable. Like NotFound
 * it carries no event, so it shows a neutral notice rather than an invitation.
 */
@Composable
private fun LoadFailedPhase(onRetry: () -> Unit, onCancel: () -> Unit) = PhaseScaffold(
    body = {
        CenteredBody {
            AppNoticeCard(
                icon = JoinNoticeOffline,
                title = "Couldn't load the event",
                body = "Check your connection and try again.",
            )
        }
    },
    actions = {
        PrimaryButton(label = "Retry", onClick = onRetry)
        SecondaryButton(label = "Cancel", onClick = onCancel)
    },
)

/**
 * We know the event (the name is carried), so the hero stays pinned above calm progress. In flight, so
 * no actions.
 */
@Composable
private fun CommittingPhase(name: String) = PhaseScaffold(
    body = {
        AppEventHeaderCompact(title = name, subtitle = JOIN_HERO_SUBTITLE)
        CenteredBody { AppJoinProgress("Joining …") }
    },
)

/**
 * The join failed after the event loaded, so the invitation stays honest above a neutral retryable
 * notice — no teleport back from Committing. The retry re-sends the range the Ready phase committed,
 * which survives Ready -> Committing -> CommitFailed because the screen stays mounted throughout.
 */
@Composable
private fun CommitFailedPhase(
    name: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) = PhaseScaffold(
    body = {
        AppEventHeaderCompact(title = name, subtitle = JOIN_HERO_SUBTITLE)
        CenteredBody {
            AppNoticeCard(
                icon = JoinNoticeFailed,
                title = "Couldn't join",
                body = "Something went wrong. Try again.",
            )
        }
    },
    actions = {
        PrimaryButton(label = "Retry", onClick = onRetry)
        SecondaryButton(label = "Cancel", onClick = onCancel)
    },
)

/**
 * The remaining vertical space of a phase body, with its content centered. Used by the phases whose body
 * is a single calm block — a spinner or a notice card — beneath (or instead of) the invitation hero.
 */
@Composable
private fun ColumnScope.CenteredBody(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

