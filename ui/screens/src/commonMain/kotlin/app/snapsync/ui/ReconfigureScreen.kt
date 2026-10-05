package app.snapsync.ui

import app.snapsync.model.AlbumKind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.model.EventConfig
import app.snapsync.model.JoinedSurface
import app.snapsync.ui.components.appRangeLabel
import app.snapsync.model.Layer
import app.snapsync.model.JoinPhase
import app.snapsync.model.PendingSwitch
import app.snapsync.model.UiState
import app.snapsync.ui.components.AppConfirmDialog
import app.snapsync.ui.components.AppDestructiveConfirmDialog
import app.snapsync.ui.components.AppIdentityHeader
import app.snapsync.ui.components.PrimaryButton
import app.snapsync.ui.components.SecondaryButton
import app.snapsync.ui.components.StatusHint
import androidx.compose.foundation.layout.ColumnScope
import app.snapsync.ui.components.DialogCopy
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.album_existing
import app.snapsync.ui.resources.album_folder_receive_existing
import app.snapsync.ui.resources.album_none
import app.snapsync.ui.resources.cancel
import app.snapsync.ui.resources.event_closed_body
import app.snapsync.ui.resources.event_closed_title
import app.snapsync.ui.resources.event_not_found_body
import app.snapsync.ui.resources.event_not_found_title
import app.snapsync.ui.resources.event_settings
import app.snapsync.ui.resources.join_both_off
import app.snapsync.ui.resources.load_failed_body
import app.snapsync.ui.resources.load_failed_title
import app.snapsync.ui.resources.ok
import app.snapsync.ui.resources.retry
import app.snapsync.ui.resources.save
import app.snapsync.ui.resources.settings_save_failed
import app.snapsync.ui.resources.settings_stop_sharing_note
import app.snapsync.ui.resources.switch_body
import app.snapsync.ui.resources.switch_confirm
import app.snapsync.ui.resources.switch_title
import app.snapsync.ui.resources.this_event
import org.jetbrains.compose.resources.stringResource

// In-place membership reconfigure (capability `manage-membership`) and the switch confirmation
// that guards a change of event.

/**
 * The **reconfigure** surface (capability `manage-membership`): a joined member re-opens the three
 * participation settings they picked at join — the two switches (Share / Receive → direction), the
 * capture-date cutoff, and the album opt-in — and changes them **in place**, without leaving.
 *
 * It renders the same [ParticipationSections] as the join gate, so there is one decision surface,
 * differing only in that it is **pre-filled** from the current [membership] and commits with **Save** (not
 * Join) beneath a read-only event-name header.
 *
 * The range preset is **reconstructed** from the persisted bounds, which is lossy by construction: a range
 * spanning the whole window seeds **Whole event** and anything narrower seeds a **custom** range — an
 * original "From now" pick is unrecoverable (decision record `simplify-join-screen`, D1). The chosen cutoff
 * is re-clamped to the `startsAt` floor on the far side, in `ReconfigureEvent`.
 *
 * Consequences are surfaced as **inline helper text**, never a blocking dialog (Save is the confirmation):
 * turning the album on states that it also collects the photos already synced, and a standing line states
 * what narrowing does: it stops listing the affected photos to the event, while anyone who already received
 * them keeps them and the member's own received photos are untouched. Both switches off disables Save with a reason,
 * exactly as the join surface disables Join.
 */
@Composable
internal fun ReconfigureScreen(
    membership: EventConfig,
    surface: JoinedSurface.Reconfigure,
    participation: ParticipationActions,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    // No local state: the member's picks and what they resolve to are both reduced (capability
    // `sync-status`). Seeding — lossy by construction, reconstructed from the persisted
    // timestamps — happens where the surface is opened, so a foreground refresh landing mid-edit updates
    // the heading and not the controls in the member's hand.
    val range = surface.range

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Read-only header: which event's settings these are. No eyebrow: this is not an invitation, so the
            // join gate's "YOU'RE INVITED" header does not belong here, and the subtitle already says what it is.
            val subtitle = stringResource(Res.string.event_settings)
            AppIdentityHeader(eyebrow = null, title = membership.name, subtitle = subtitle)
            // The last Save did not land (capability `manage-membership`): the edits are still here, and
            // nothing about the membership changed — said plainly, so the member knows a retry is safe.
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
        SaveActions(enabled = range.commitEnabled, onSave = onSave, onCancel = onCancel)
    }
}

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
        is Layer.Joined -> layer.pendingSwitch
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
        JoinPhase.NotFound ->
            AppConfirmDialog(
                copy = DialogCopy(
                    title = stringResource(Res.string.event_not_found_title),
                    body = stringResource(Res.string.event_not_found_body),
                    confirmLabel = stringResource(Res.string.ok),
                    cancelLabel = stringResource(Res.string.cancel),
                ),
                onConfirm = onCancelSwitch,
                onDismiss = onCancelSwitch,
            )
        // A member opening a closed event's invite stays in their own event (capability `join-event`).
        JoinPhase.Closed ->
            AppConfirmDialog(
                copy = DialogCopy(
                    title = stringResource(Res.string.event_closed_title),
                    body = stringResource(Res.string.event_closed_body),
                    confirmLabel = stringResource(Res.string.ok),
                    cancelLabel = stringResource(Res.string.cancel),
                ),
                onConfirm = onCancelSwitch,
                onDismiss = onCancelSwitch,
            )
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

/**
 * Save and Cancel, over the standing statement of what changing these settings does.
 *
 * That line used to say a change "never retracts photos already shared or received", and half of that
 * became false: narrowing what you share now re-projects the device manifest, so those photos stop being
 * listed to the event (capability `manage-membership`).
 *
 * What it must NOT imply is deletion. The retraction is partial by nature — SnapSync syncs
 * gallery-to-gallery, so a member who already downloaded the photo holds it in their own library and
 * nothing here reaches it. Receiving is unaffected either way.
 */
@Composable
private fun ColumnScope.SaveActions(enabled: Boolean, onSave: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusHint(stringResource(Res.string.settings_stop_sharing_note))
        if (!enabled) StatusHint(stringResource(Res.string.join_both_off))
        PrimaryButton(label = stringResource(Res.string.save), onClick = onSave, enabled = enabled)
        SecondaryButton(label = stringResource(Res.string.cancel), onClick = onCancel)
    }
}
