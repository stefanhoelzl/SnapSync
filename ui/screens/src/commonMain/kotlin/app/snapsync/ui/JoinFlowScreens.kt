package app.snapsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.model.JoinPhase
import app.snapsync.model.JoinStage
import app.snapsync.model.Layer
import app.snapsync.model.NetworkNotice
import app.snapsync.model.ScreenMessage
import app.snapsync.ui.components.AppErrorBanner
import app.snapsync.ui.components.AppEventHeaderCompact
import app.snapsync.ui.components.AppInvitationHeaderLoading
import app.snapsync.ui.components.AppJoinProgress
import app.snapsync.ui.components.AppNetworkNotice
import app.snapsync.ui.components.AppNoticeCard
import app.snapsync.ui.components.JoinNoticeFailed
import app.snapsync.ui.components.JoinNoticeInvalid
import app.snapsync.ui.components.JoinNoticeOffline
import app.snapsync.ui.components.PrimaryButton
import app.snapsync.ui.components.SecondaryButton
import app.snapsync.ui.components.appRangeLabel
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.cancel
import app.snapsync.ui.resources.event_full_body
import app.snapsync.ui.resources.event_full_title
import app.snapsync.ui.resources.join_failed_body
import app.snapsync.ui.resources.join_failed_title
import app.snapsync.ui.resources.joining
import app.snapsync.ui.resources.load_failed_body
import app.snapsync.ui.resources.load_failed_title
import app.snapsync.ui.resources.loading_event
import app.snapsync.ui.resources.message_report_this
import app.snapsync.ui.resources.retry
import app.snapsync.ui.resources.waiting_network_body
import app.snapsync.ui.resources.waiting_network_title
import org.jetbrains.compose.resources.stringResource

// The join gate: the full-screen surface a scanned link opens, and the
// status-plus-actions phases it dispatches over. The Ready decision surface lives in
// `JoinReadySurface.kt`, the phase-window accessors with the derivation that reads them in
// `JoinSelection.kt`.

/**
 * The full-screen "Join event" surface: the event summary is the hero, the
 * participation choices beneath it (share and receive, which photos, the album), with Join / Cancel pinned
 * to the bottom. Renders each [JoinPhase]: loading details, ready-to-join, blocked (invalid invite, full
 * event), a retryable load/commit failure.
 *
 * Nothing is held here: the choices are reduced state ([Layer.JoiningEvent.form]) and survive
 * Ready → Committing → CommitFailed, so a retry commits what the member picked.
 */
@Composable
internal fun JoiningEventScreen(
    layer: Layer.JoiningEvent,
    actions: JoinActions,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // A rejected event link that arrived while this surface is open (the
        // self-clearing "not valid" message shows on whatever screen the user is on). Above the phase for the
        // joined layer's reason — it is about what the user JUST DID — and it changes nothing below.
        layer.notice?.let { AppErrorBanner(it.text()) }
        // A missing network — without one, the join screen waits for one — is said above the
        // phase, and every step that would reach the backend — Join, a retried join, a retried load — waits.
        val network = layer.network
        if (network != null) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                AppNetworkNotice(blocked = network == NetworkNotice.BLOCKED, onOpenSettings = actions.onOpenSettings)
            }
        }
        val online = network == null
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Two levels, and the nesting IS the type: the phases that carry no event, then the loaded one
            // dispatched on its step. Every `when` is exhaustive, so a new phase or a new step fails the compile
            // rather than falling through — and no branch reaches for details, or a range, a phase might not have.
            when (val stage = layer.stage) {
                is JoinStage.Unloaded -> when (val phase = stage.phase) {
                    JoinPhase.Loading -> LoadingPhase()
                    // The walls no retry moves — see [wallCopy].
                    JoinPhase.NotFound, JoinPhase.Closed, JoinPhase.WrongLink -> {
                        val copy = wallCopy(phase)
                        WallPhase(stringResource(copy.title), stringResource(copy.body), actions.onCancel)
                    }
                    // Without a network the details load by themselves once it returns, so there is nothing to retry.
                    JoinPhase.LoadFailed -> if (online) {
                        LoadFailedPhase(onRetry = actions.onRetryLoad, onCancel = actions.onCancel)
                    } else {
                        WaitingForNetworkPhase(actions.onCancel)
                    }
                }
                is JoinStage.Loaded -> when (stage.phase.step) {
                    // The one step that is a *decision surface* rather than a status-plus-actions surface, so it
                    // owns its whole layout instead of the scaffold every other step opts into.
                    JoinPhase.Detailed.Step.Ready -> ReadyLayout(
                        state = readyState(stage, layer, online),
                        actions = ReadyActions(
                            participation = actions.participation,
                            onJoin = actions.onConfirm,
                            onCancel = actions.onCancel,
                        ),
                    )
                    JoinPhase.Detailed.Step.Committing -> CommittingPhase(name = stage.phase.event.name)
                    // Every way a commit can end badly, on ONE surface — see [BlockedStep].
                    JoinPhase.Detailed.Step.CommitFailed, JoinPhase.Detailed.Step.EventFull,
                    JoinPhase.Detailed.Step.DeviceRefused,
                    -> BlockedStep(stage.phase, actions, online)
                }
            }
        }
    }
}

/**
 * A commit that did not land, after the event loaded: the invitation stays honest above a neutral notice — no
 * teleport back from Committing. ONE surface for every way a commit ends badly, because they differ only in the copy
 * and in which actions are offered — a second near-identical composable would drift from this one the first time
 * either was touched:
 *
 * - a failure that may heal keeps a Retry, which re-sends the range the Ready phase committed (it survives Ready →
 *   Committing → CommitFailed because the screen stays mounted throughout);
 * - a FULL event gets none: capacity does not heal, so a Retry would fail identically every time and turn a clear
 *   answer into a member pressing a button against a wall;
 * - a phone the service refused — a refused phone is told why it cannot join — is told the
 *   cause, keeps a Retry that tries to verify it again, and — where only a report can help — offers the report.
 *
 * Cancel is the way out of every one.
 */
@Composable
private fun BlockedStep(phase: JoinPhase.Detailed, actions: JoinActions, online: Boolean) {
    val refused = phase.step == JoinPhase.Detailed.Step.DeviceRefused
    val refusal = (phase.refusal ?: ScreenMessage.DEVICE_UNVERIFIABLE).takeIf { refused }
    val full = phase.step == JoinPhase.Detailed.Step.EventFull
    val title = stringResource(if (full) Res.string.event_full_title else Res.string.join_failed_title)
    val body = refusal?.text() ?: stringResource(if (full) Res.string.event_full_body else Res.string.join_failed_body)
    val onRetry = actions.onRetryJoin.takeIf { online && !full }
    val onReport = refusal?.takeIf { it.offersReport }?.let { reportable ->
        {
            actions.onReportRefusal(reportable)
        }
    }
    PhaseScaffold(
        body = {
            AppEventHeaderCompact(title = phase.event.name)
            CenteredBody {
                AppNoticeCard(icon = JoinNoticeFailed, title = title, body = body)
            }
        },
        actions = {
            onRetry?.let { PrimaryButton(label = stringResource(Res.string.retry), onClick = it) }
            onReport?.let { SecondaryButton(label = stringResource(Res.string.message_report_this), onClick = it) }
            SecondaryButton(label = stringResource(Res.string.cancel), onClick = actions.onCancel)
        },
    )
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
 * the space below. Resolves into Ready with no header jump — the badge and eyebrow never move across
 * Loading -> Ready -> Committing, only the name resolves.
 */
@Composable
private fun LoadingPhase() = PhaseScaffold(
    body = {
        AppInvitationHeaderLoading()
        CenteredBody { AppJoinProgress(stringResource(Res.string.loading_event)) }
    },
)

/**
 * A dead end — the event does not exist, or has closed — so no invitation hero, just an
 * honest notice. There is nothing to be invited to, so this never shows a false invitation; Cancel is the only way
 * out, because no retry moves either wall.
 */
@Composable
private fun WallPhase(title: String, body: String, onCancel: () -> Unit) = PhaseScaffold(
    body = {
        CenteredBody {
            AppNoticeCard(
                icon = JoinNoticeInvalid,
                title = title,
                body = body,
            )
        }
    },
    actions = { SecondaryButton(label = stringResource(Res.string.cancel), onClick = onCancel) },
)

/**
 * The details could not load for want of a network: the notice above names the cause, and
 * the details load by themselves once it returns — so the only action is Cancel.
 */
@Composable
private fun WaitingForNetworkPhase(onCancel: () -> Unit) = PhaseScaffold(
    body = {
        CenteredBody {
            AppNoticeCard(
                icon = JoinNoticeOffline,
                title = stringResource(Res.string.waiting_network_title),
                body = stringResource(Res.string.waiting_network_body),
            )
        }
    },
    actions = { SecondaryButton(label = stringResource(Res.string.cancel), onClick = onCancel) },
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
                title = stringResource(Res.string.load_failed_title),
                body = stringResource(Res.string.load_failed_body),
            )
        }
    },
    actions = {
        PrimaryButton(label = stringResource(Res.string.retry), onClick = onRetry)
        SecondaryButton(label = stringResource(Res.string.cancel), onClick = onCancel)
    },
)

/**
 * We know the event (the name is carried), so the hero stays pinned above calm progress. In flight, so
 * no actions.
 */
@Composable
private fun CommittingPhase(name: String) = PhaseScaffold(
    body = {
        AppEventHeaderCompact(title = name)
        CenteredBody { AppJoinProgress(stringResource(Res.string.joining)) }
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
@Composable
private fun readyState(
    stage: JoinStage.Loaded,
    layer: Layer.JoiningEvent,
    online: Boolean,
): ReadyState {
    val range = stage.range
    return ReadyState(
        eventName = stage.phase.event.name,
        // The switches come off the FORM, never back off `range.direction`: `directionOf` collapses both-off
        // to `DownloadOnly` as an inert placeholder, so deriving them there would render the receive switch
        // ON for a member who had turned both off.
        participation = ParticipationState(
            form = layer.form,
            range = range,
            rangeLabel = appRangeLabel(range.from, range.until),
        ),
        asksAccessOnJoin = layer.asksAccessOnJoin,
        online = online,
    )
}

/**
 * Everything the join gate can ask for: the two ways to commit, the ways out, and the member's edits.
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
    val onCancel: () -> Unit,
    val onRetryLoad: () -> Unit,
    /** The member's edits to the range form, bound to the container's intents. */
    val participation: ParticipationActions,
    /** SnapSync's Settings page, offered while its network is blocked. */
    val onOpenSettings: () -> Unit,
    /** "Report this" beside a refusal the user can only tell us about. */
    val onReportRefusal: (ScreenMessage) -> Unit,
)
