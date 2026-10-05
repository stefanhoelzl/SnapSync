package app.snapsync.ui

import androidx.compose.foundation.layout.padding
import app.snapsync.ui.components.AppNetworkNotice
import app.snapsync.model.NetworkNotice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.model.EventDetails
import app.snapsync.model.Layer
import app.snapsync.model.JoinPhase
import app.snapsync.ui.components.AppErrorBanner
import app.snapsync.ui.components.AppInvitationHeaderLoading
import app.snapsync.ui.components.AppJoinProgress
import app.snapsync.ui.components.AppNoticeCard
import app.snapsync.ui.components.JoinNoticeFailed
import app.snapsync.ui.components.JoinNoticeInvalid
import app.snapsync.ui.components.JoinNoticeOffline
import app.snapsync.ui.components.AppEventHeaderCompact
import app.snapsync.ui.components.appRangeLabel
import app.snapsync.ui.components.PrimaryButton
import app.snapsync.ui.components.SecondaryButton
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.event_closed_body
import app.snapsync.ui.resources.event_closed_title
import app.snapsync.ui.resources.event_full_body
import app.snapsync.ui.resources.event_full_title
import app.snapsync.ui.resources.event_not_found_body
import app.snapsync.ui.resources.event_not_found_title
import app.snapsync.ui.resources.hero_subtitle
import app.snapsync.ui.resources.join_failed_body
import app.snapsync.ui.resources.join_failed_title
import app.snapsync.ui.resources.joining
import app.snapsync.ui.resources.load_failed_body
import app.snapsync.ui.resources.load_failed_title
import app.snapsync.ui.resources.loading_event
import app.snapsync.ui.resources.retry
import app.snapsync.ui.resources.waiting_network_body
import app.snapsync.ui.resources.waiting_network_title
import org.jetbrains.compose.resources.stringResource
import app.snapsync.ui.resources.cancel

// The join gate (capability `join-event`): the full-screen surface a scanned link opens, and the
// status-plus-actions phases it dispatches over. The Ready decision surface lives in
// `JoinReadySurface.kt`, the phase-window accessors with the derivation that reads them in
// `JoinSelection.kt`.


/**
 * The full-screen "Join event" surface (capability `join-event`): the event summary is the hero, the
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
    val phase = layer.phase
    Column(modifier = Modifier.fillMaxSize()) {
        // A rejected event link that arrived while this surface is open (capability `join-event`: the
        // self-clearing "not valid" message shows on whatever screen the user is on). Above the phase for the
        // joined layer's reason — it is about what the user JUST DID — and it changes nothing below.
        layer.notice?.let { AppErrorBanner(it.text()) }
        // A missing network (capability `join-event`, "Without a network, the join screen waits for one"): said above
        // the phase, and every step that would reach the backend — Join, a retried join, a retried load — waits.
        val network = layer.network
        if (network != null) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                AppNetworkNotice(blocked = network == NetworkNotice.BLOCKED, onOpenSettings = actions.onOpenSettings)
            }
        }
        val online = network == null
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Two levels, and the nesting IS the type: the four phases that carry no event, then the loaded
            // one dispatched on its step. Both `when`s are exhaustive, so a new phase or a new step fails the
            // compile rather than falling through — and no branch reaches for details a phase might not have.
            when (phase) {
                JoinPhase.Loading -> LoadingPhase()
                JoinPhase.NotFound -> WallPhase(
                    title = stringResource(Res.string.event_not_found_title),
                    body = stringResource(Res.string.event_not_found_body),
                    onCancel = actions.onCancel,
                )
                JoinPhase.Closed -> WallPhase(
                    stringResource(Res.string.event_closed_title),
                    stringResource(Res.string.event_closed_body),
                    actions.onCancel,
                )
                // Without a network the details load by themselves once it returns, so there is nothing to retry.
                JoinPhase.LoadFailed -> if (online) {
                    LoadFailedPhase(onRetry = actions.onRetryLoad, onCancel = actions.onCancel)
                } else {
                    WaitingForNetworkPhase(actions.onCancel)
                }
                is JoinPhase.Detailed -> when (phase.step) {
                    // The one step that is a *decision surface* rather than a status-plus-actions surface, so it
                    // owns its whole layout instead of the scaffold every other step opts into.
                    JoinPhase.Detailed.Step.Ready -> ReadyLayout(
                        state = readyState(phase.event, layer, online),
                        actions = ReadyActions(
                            participation = actions.participation,
                            onJoin = actions.onConfirm,
                            onCancel = actions.onCancel,
                        ),
                    )
                    JoinPhase.Detailed.Step.Committing -> CommittingPhase(name = phase.event.name)
                    // The two ways a commit can end badly, on ONE surface distinguished by its copy and by
                    // whether a Retry is offered at all — see [CommitBlockedPhase].
                    JoinPhase.Detailed.Step.CommitFailed -> CommitBlockedPhase(
                        name = phase.event.name,
                        title = stringResource(Res.string.join_failed_title),
                        body = stringResource(Res.string.join_failed_body),
                        onRetry = actions.onRetryJoin.takeIf { online },
                        onCancel = actions.onCancel,
                    )
                    JoinPhase.Detailed.Step.EventFull -> CommitBlockedPhase(
                        name = phase.event.name,
                        title = stringResource(Res.string.event_full_title),
                        body = stringResource(Res.string.event_full_body),
                        onRetry = null,
                        onCancel = actions.onCancel,
                    )
                }
            }
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
 * the space below. Resolves into Ready with no header jump — the badge and eyebrow never move across
 * Loading -> Ready -> Committing, only the name resolves.
 */
@Composable
private fun LoadingPhase() = PhaseScaffold(
    body = {
        AppInvitationHeaderLoading(subtitle = stringResource(Res.string.hero_subtitle))
        CenteredBody { AppJoinProgress(stringResource(Res.string.loading_event)) }
    },
)

/**
 * A dead end — the event does not exist, or has closed (capability `join-event`) — so no invitation hero, just an
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
 * The details could not load for want of a network (capability `join-event`): the notice above names the cause, and
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
        AppEventHeaderCompact(title = name, subtitle = stringResource(Res.string.hero_subtitle))
        CenteredBody { AppJoinProgress(stringResource(Res.string.joining)) }
    },
)

/**
 * A commit that did not land, after the event loaded: the invitation stays honest above a neutral
 * notice — no teleport back from Committing.
 *
 * **[onRetry] is nullable, and that nullability IS the difference between the two states this serves.**
 * A transient failure gets a Retry, which re-sends the range the Ready phase committed (it survives
 * Ready → Committing → CommitFailed because the screen stays mounted throughout). A FULL event gets
 * none: capacity does not heal, so a Retry would fail identically every time and turn a clear answer
 * into a member pressing a button against a wall. Cancel is the way out of both.
 *
 * One surface rather than two because they differ in exactly this — the copy and that one affordance —
 * and a second near-identical composable would drift from this one the first time either was touched.
 */
@Composable
private fun CommitBlockedPhase(
    name: String,
    title: String,
    body: String,
    onRetry: (() -> Unit)?,
    onCancel: () -> Unit,
) = PhaseScaffold(
    body = {
        AppEventHeaderCompact(title = name, subtitle = stringResource(Res.string.hero_subtitle))
        CenteredBody {
            AppNoticeCard(icon = JoinNoticeFailed, title = title, body = body)
        }
    },
    actions = {
        onRetry?.let { PrimaryButton(label = stringResource(Res.string.retry), onClick = it) }
        SecondaryButton(label = stringResource(Res.string.cancel), onClick = onCancel)
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
    event: EventDetails,
    layer: Layer.JoiningEvent,
    online: Boolean,
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
    /** SnapSync's Settings page, offered while its network is blocked (capability `join-event`). */
    val onOpenSettings: () -> Unit,
)
