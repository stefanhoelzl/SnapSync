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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.model.Direction
import app.snapsync.model.PermissionStatus
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.EventDetails
import app.snapsync.presentation.Layer
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
import app.snapsync.model.FromChoice
import app.snapsync.model.UntilChoice
import app.snapsync.ui.components.AppEventHeaderCompact
import app.snapsync.ui.components.AppSectionNote
import app.snapsync.ui.components.AppMinorSection
import app.snapsync.ui.components.AppSectionValue
import app.snapsync.ui.components.AppSummaryToggle
import app.snapsync.ui.components.AppToggleSection
import app.snapsync.ui.components.appDateLabel
import app.snapsync.ui.components.appDateTimeLabel
import app.snapsync.ui.components.appRangeLabel
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
    layer: Layer.JoiningEvent,
    actions: JoinActions,
    photoPermission: PermissionStatus,
) {
    val phase = layer.phase
    // Two levels, and the nesting IS the type: the three phases that carry no event, then the loaded
    // one dispatched on its step. Both `when`s are exhaustive, so a new phase or a new step fails the
    // compile rather than falling through — and no branch reaches for details a phase might not have.
    when (phase) {
        JoinPhase.Loading -> LoadingPhase()
        JoinPhase.NotFound -> NotFoundPhase(onCancel = actions.onCancel)
        JoinPhase.LoadFailed -> LoadFailedPhase(onRetry = actions.onRetryLoad, onCancel = actions.onCancel)
        is JoinPhase.Detailed -> when (phase.step) {
            // The one step that is a *decision surface* rather than a status-plus-actions surface, so it
            // owns its whole layout instead of the scaffold every other step opts into.
            JoinPhase.Detailed.Step.Ready -> ReadyLayout(
                state = readyState(phase.event, layer, photoPermission),
                actions = ReadyActions(
                    participation = actions.participation,
                    onJoin = actions.onConfirm,
                    onCancel = actions.onCancel,
                ),
            )
            JoinPhase.Detailed.Step.ExplainAccess -> ExplainAccessPhase(
                name = phase.event.name,
                onAcknowledge = actions.onAcknowledgeAccess,
                onCancel = actions.onCancel,
            )
            JoinPhase.Detailed.Step.Committing -> CommittingPhase(name = phase.event.name)
            JoinPhase.Detailed.Step.CommitFailed -> CommitFailedPhase(
                name = phase.event.name,
                onRetry = actions.onRetryJoin,
                onCancel = actions.onCancel,
            )
        }
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

/**
 * What the Ready surface displays, assembled from the phase and the member's live picks.
 *
 * A plain function and not a composable — it reads nothing but its arguments. Pulled out for the reason
 * `reconfigureNotes` was on the other surface: the assembly is bulk, it answers "what does Ready show"
 * rather than "what does this screen draw", and burying it in a `when` branch made the dispatcher above
 * unreadable as a dispatcher.
 */
private fun readyState(
    event: EventDetails,
    layer: Layer.JoiningEvent,
    photoPermission: PermissionStatus,
): ReadyState {
    // Non-null by construction on a loaded phase: the reduction resolves the range wherever there is a
    // window, and this surface renders only where there is one.
    val range = layer.range ?: error("a loaded join phase always resolves a range")
    return ReadyState(
    eventName = event.name,
    // The switches come off the FORM, never back off `range.direction`: `directionOf` collapses both-off
    // to `DownloadOnly` as an inert placeholder, so deriving them there would render the receive switch
    // ON for a member who had turned both off.
    participation = ParticipationState(
        form = layer.form,
        range = range,
        rangeLabel = appRangeLabel(range.from, range.until),
        photoPermission = photoPermission,
    ),
    labels = ReadyLabels(
        floor = appDateTimeLabel(range.windowStart),
        ceiling = appDateTimeLabel(range.windowEnd),
        // The retention deadline, rendered as a plain date. A loaded phase always carries one now — it
        // is a field of the details, not something a phase could have lost — so the only absence left is
        // an unparseable instant, which the section renders as the fixed ceiling alone.
        deletes = appDateLabel(range.deletesLocal ?: range.windowEnd),
    ),
    )
}

/**
 * Everything the join gate can ask for: the two ways to commit, the three ways out, and the count query.
 *
 * The five callbacks were loose parameters interleaved with the two values the screen renders from, which
 * is the shape `ReadyLayout` was cured of — a surface's inputs and its outputs read better separated than
 * alternating.
 *
 * [onRetryJoin] is distinct from [onConfirm] because a retry commits WITHOUT passing back through the
 * loaded phase, and [onCancel] is the one every phase pins.
 */
internal class JoinActions(
    // The commits carry NOTHING: what would be committed is what the reduction already resolved, so
    // handing values back from the render path would be a second answer to a settled question.
    val onConfirm: () -> Unit,
    val onRetryJoin: () -> Unit,
    val onAcknowledgeAccess: () -> Unit,
    val onCancel: () -> Unit,
    val onRetryLoad: () -> Unit,
    /** The member's edits to the range form, bound to the container's intents. */
    val participation: ParticipationActions,
)
