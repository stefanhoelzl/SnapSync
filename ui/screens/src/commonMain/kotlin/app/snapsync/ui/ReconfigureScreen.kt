package app.snapsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import app.snapsync.model.AlbumKind
import app.snapsync.model.JoinPhase
import app.snapsync.model.JoinedSurface
import app.snapsync.model.Layer
import app.snapsync.model.PendingSwitch
import app.snapsync.model.UiState
import app.snapsync.ui.components.AppConfirmDialog
import app.snapsync.ui.components.AppDestructiveConfirmDialog
import app.snapsync.ui.components.AppPageSheet
import app.snapsync.ui.components.DialogCopy
import app.snapsync.ui.components.StatusHint
import app.snapsync.ui.components.appRangeLabel
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.album_existing
import app.snapsync.ui.resources.album_folder_receive_existing
import app.snapsync.ui.resources.album_none
import app.snapsync.ui.resources.cancel
import app.snapsync.ui.resources.load_failed_body
import app.snapsync.ui.resources.load_failed_title
import app.snapsync.ui.resources.ok
import app.snapsync.ui.resources.retry
import app.snapsync.ui.resources.settings_save_failed
import app.snapsync.ui.resources.stop_sharing_body
import app.snapsync.ui.resources.stop_sharing_confirm
import app.snapsync.ui.resources.stop_sharing_keep
import app.snapsync.ui.resources.stop_sharing_title
import app.snapsync.ui.resources.switch_body
import app.snapsync.ui.resources.switch_confirm
import app.snapsync.ui.resources.switch_title
import app.snapsync.ui.resources.this_event
import org.jetbrains.compose.resources.stringResource

// In-place membership reconfigure (capability `manage-membership`) and the switch confirmation
// that guards a change of event.

/**
 * The event's **settings** (capability `manage-membership`), in a sheet over the joined screen: a joined member
 * re-opens the choices they made at join — share and receive, the capture range, the album — and each
 * change applies as it is made. There is no Save, Cancel or header: the sheet's drag handle is its only chrome, and
 * swiping it down, going back, or tapping the joined screen above it all call [onClose].
 *
 * It renders the same [ParticipationSections] as the join gate, so there is one decision surface, showing the
 * membership in effect. The range preset is **reconstructed** from the persisted bounds, which is lossy by
 * construction: a range spanning the whole window shows **Whole event** and anything narrower a **custom** range
 * (decision record `simplify-join-screen`, D1).
 *
 * A change that would withdraw photos from the event — sharing off, a narrower range — is held while
 * [JoinedSurface.Reconfigure.askingToStopSharing] asks first, saying what narrowing does: the photos reach no one new,
 * anyone who already received them keeps them, and the member's own received photos stay. Turning the album on says
 * inline that it also collects the photos already synced.
 */
@Composable
internal fun ReconfigureSheet(
    surface: JoinedSurface.Reconfigure,
    participation: ParticipationActions,
    withdrawal: WithdrawalActions,
    onClose: () -> Unit,
) {
    val range = surface.range
    AppPageSheet(onDismiss = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // The last change did not land (capability `manage-membership`): the control already shows the setting
            // still in effect, and this says so plainly, so the member knows to try again.
            if (surface.saveFailed) StatusHint(stringResource(Res.string.settings_save_failed))
            ParticipationSections(
                state = ParticipationState(
                    form = surface.form,
                    range = range,
                    rangeLabel = appRangeLabel(range.from, range.until),
                ),
                actions = participation,
                albumNote = reconfigureAlbumNote(surface.form.saveToAlbum, surface.form.albumKind),
            )
        }
    }
    if (surface.askingToStopSharing) {
        // Destructive, like Leave: confirming withdraws the member's photos from the event.
        AppDestructiveConfirmDialog(
            copy = DialogCopy(
                title = stringResource(Res.string.stop_sharing_title),
                body = stringResource(Res.string.stop_sharing_body),
                confirmLabel = stringResource(Res.string.stop_sharing_confirm),
                cancelLabel = stringResource(Res.string.stop_sharing_keep),
            ),
            onConfirm = withdrawal.onStopSharing,
            onDismiss = withdrawal.onKeepSharing,
        )
    }
}

/** The answers to the settings' "Stop sharing these photos?". */
class WithdrawalActions(val onStopSharing: () -> Unit, val onKeepSharing: () -> Unit)

/**
 * What the operator was looking at when they wrote a report (capability `privacy-security`).
 *
 * It is derived **here** rather than in the container because the two surfaces worth naming are
 * screen-local: the reconfigure surface and, over the joined layer, a pending switch. Both are Compose
 * state that touches no port by design, so they appear in no log line, no ledger row, and nowhere in
 * `UiState` a container could read — this label is the only route by which they reach a report.
 *
 * Deliberately coarse: a surface name, plus the join phase where there is one (a gate stuck on
 * `LoadFailed` is a different report from one parked on `Ready`). It carries no event id or user data —
 * those are already in the state section, in a field that says what they are.
 */
// A label for the report's reader, never shown on a screen: it names code, so it is not translated.
@Suppress("HardCodedUiText")
internal fun screenLabel(state: UiState): String {
    val layer = state.layer
    if (layer is Layer.Joined && layer.surface is JoinedSurface.Reconfigure) return "Reconfigure"
    return when (layer) {
        is Layer.JoiningEvent -> "JoiningEvent:${layer.phase::class.simpleName}"
        is Layer.Joined ->
            layer.pendingSwitch
                ?.let { "Switch:${it.phase::class.simpleName}" }
                ?: "Joined"
        is Layer.CreateEvent -> "CreateEvent"
        Layer.CreatingEvent -> "CreatingEvent"
        is Layer.UpdateRequired -> "UpdateRequired"
    }
}

/**
 * The switch confirmation (a different event scanned while joined) — the leave-style dialog. Its confirm
 * runs the **leave and nothing else** (capability `join-event`); the join that follows is the regular
 * full-screen surface, which the reduction presents once the leave has cleared the config. So this dialog
 * carries no pickers, decides nothing, and commits nothing.
 *
 * Mirrors the join phases in a compact `AppConfirmDialog`: the loaded phase offers Switch; a load failure
 * offers Retry; a missing event dismisses. Transient loading/committing phases show nothing. There is no
 * commit-failure branch: the leave precedes any commit, so a commit can never fail while a config is
 * still present — that phase belongs to the full-screen surface now.
 */
@Composable
internal fun SwitchDialog(
    switch: PendingSwitch,
    currentEventName: String?,
    onConfirmSwitch: () -> Unit,
    onCancelSwitch: () -> Unit,
    onRetryLoad: () -> Unit,
) {
    val current = currentEventName ?: stringResource(Res.string.this_event)
    when (val phase = switch.phase) {
        // Only the Ready step opens a confirmation here. The others are unreachable in this overlay and
        // are collapsed deliberately below, each with the reason it cannot occur.
        is JoinPhase.Detailed -> if (phase.step != JoinPhase.Detailed.Step.Ready) {
            // CommitFailed cannot occur: this dialog's confirm runs only the leave, so no commit can fail
            // while the previous event is still configured. Committing is transient — no dialog while a
            // commit runs.
        } else {
            AppDestructiveConfirmDialog(
                // The names carry the whole weight of the decision, so they are the whole body; the title
                // is the crisp question. Destructive, because the confirm leaves immediately. It promises
                // NO participation — the member picks direction, cutoff and album on the join surface that
                // follows — and shows no shareable count, there being no chosen range to count yet
                // (capability `join-event`).
                copy = DialogCopy(
                    title = stringResource(Res.string.switch_title),
                    confirmLabel = stringResource(Res.string.switch_confirm),
                    cancelLabel = stringResource(Res.string.cancel),
                    body = stringResource(Res.string.switch_body, current, phase.event.name),
                ),
                onConfirm = onConfirmSwitch,
                onDismiss = onCancelSwitch,
            )
        }
        // An invite to no event, a closed one, or an incomplete invite of an encrypted one: the member stays in
        // their own event (capability `join-event`) — the same walls the join screen shows.
        JoinPhase.NotFound, JoinPhase.Closed, JoinPhase.WrongLink -> {
            val copy = wallCopy(phase)
            AppConfirmDialog(
                copy = DialogCopy(
                    title = stringResource(copy.title),
                    body = stringResource(copy.body),
                    confirmLabel = stringResource(Res.string.ok),
                    cancelLabel = stringResource(Res.string.cancel),
                ),
                onConfirm = onCancelSwitch,
                onDismiss = onCancelSwitch,
            )
        }
        JoinPhase.LoadFailed ->
            AppConfirmDialog(
                copy = DialogCopy(
                    title = stringResource(Res.string.load_failed_title),
                    body = stringResource(Res.string.load_failed_body),
                    confirmLabel = stringResource(Res.string.retry),
                    cancelLabel = stringResource(Res.string.cancel),
                ),
                onConfirm = onRetryLoad,
                onDismiss = onCancelSwitch,
            )
        // Transient — no dialog while the details load.
        JoinPhase.Loading -> Unit
    }
}

/**
 * The one sentence this surface says differently from the join gate. Turning the album on gathers what the
 * device already holds (capabilities `manage-membership`, `event-album`), so the on-note says the
 * already-synced photos are included. "Synced", not "shared and received": this note does not vary with the
 * switches, and must not name a feed the membership lacks.
 */
@Composable
private fun reconfigureAlbumNote(saveToAlbum: Boolean, kind: AlbumKind): String = stringResource(
    when {
        !saveToAlbum -> Res.string.album_none
        // A folder album (Android) holds only what is received, and gathering moves the received photos into it.
        kind == AlbumKind.FOLDER -> Res.string.album_folder_receive_existing
        else -> Res.string.album_existing
    },
)
