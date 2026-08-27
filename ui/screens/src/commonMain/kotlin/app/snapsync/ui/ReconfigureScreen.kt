package app.snapsync.ui

import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureCeiling
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.PermissionStatus
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.JoinPhase
import app.snapsync.presentation.PendingSwitch
import app.snapsync.presentation.UiState
import app.snapsync.ui.components.AppConfirmDialog
import app.snapsync.ui.components.AppDestructiveConfirmDialog
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
import app.snapsync.ui.components.appDateTimeLabel
import app.snapsync.ui.components.PrimaryButton
import app.snapsync.ui.components.SecondaryButton
import app.snapsync.ui.components.StatusHint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.snapsync.ui.components.RangeChoiceActions
import app.snapsync.ui.components.RangeChoices
import androidx.compose.foundation.layout.ColumnScope

// In-place membership reconfigure (capability `reconfigure-membership`) and the switch confirmation
// that guards a change of event.

/**
 * The **reconfigure** surface (capability `reconfigure-membership`): a joined member re-opens the three
 * participation settings they picked at join — the two switches (Share / Receive → direction), the
 * capture-date cutoff, and the album opt-in — and changes them **in place**, without leaving.
 *
 * It reuses the exact join controls ([AppToggleSection], [AppRangePresetChoices], [AppMinorSection]) so there
 * is one decision surface, differing only in that it is **pre-filled** from the current [membership] and
 * commits with **Save** (not Join) beneath a read-only event-name header.
 *
 * The cutoff preset is **reconstructed** from the persisted value, which is lossy by construction: the
 * join UI's presets are not persisted, only the resulting instant, so `minPhotoDate == startsAt` seeds
 * **Event start** and anything above it seeds **Custom** — the original "Now" pick is unrecoverable
 * (design decision "cutoff pre-fill reconstruction"). The chosen cutoff is re-clamped to the `startsAt`
 * floor on the far side, in `ReconfigureEvent`.
 *
 * Consequences are surfaced as **inline helper text**, never a blocking dialog (Save is the confirmation):
 * turning the album on states it is forward-only (no backfill), and a standing line states what narrowing
 * does: it stops listing the affected photos to the event, while anyone who already received them keeps
 * them and the member's own received photos are untouched. Both switches off disables Save with a reason,
 * exactly as the join surface disables Join.
 */
@Composable
internal fun ReconfigureScreen(
    membership: EventConfig,
    cutoff: CutoffFormatter,
    shareableCount: suspend (cutoff: CaptureCutoff, until: CaptureCeiling?) -> Int?,
    photoPermission: PermissionStatus,
    onSave: (String, Direction, CaptureCutoff, CaptureCeiling, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    // The member's own picks, owned in one place. Seeded from the persisted membership — the presets
    // reconstructed from its timestamps, which is lossy by construction (see `reconfigureSeeds`).
    val seeds = remember(membership) { reconfigureSeeds(membership, cutoff) }
    val participation = rememberParticipation(
        ParticipationSeed(
            shareOn = membership.direction.includesUpload,
            receiveOn = membership.direction.includesDownload,
            saveToAlbum = membership.saveToAlbum,
            fromPreset = seeds.fromPreset,
            fromCustom = seeds.fromCustom,
            untilPreset = seeds.untilPreset,
            untilCustom = seeds.untilCustom,
        ),
    )
    // The SAME derivation the join gate runs, over the same holder — only the window differs.
    val selection = rememberReconfigureSelection(membership, cutoff, participation)


    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Read-only header: which event's settings these are.
            AppEventHeaderCompact(title = membership.name, subtitle = "Event settings")

            ParticipationSections(
                state = participation.state(
                    selection = selection,
                    rangeLabel = cutoff.formatRange(selection.fromResolved, selection.untilResolved),
                    photoPermission = photoPermission,
                ),
                actions = participation.actions(shareableCount),
                notes = reconfigureNotes(membership, selection, participation.saveToAlbum),
            )
        }
        SaveActions(
            enabled = selection.commitEnabled,
            onSave = {
                onSave(
                    membership.eventId,
                    selection.chosenDirection,
                    selection.chosenFrom,
                    selection.chosenUntil,
                    participation.saveToAlbum,
                )
            },
            onCancel = onCancel,
        )
    }
}

/**
 * What the operator was looking at when they wrote a report (capability `diagnostic-logging`).
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
internal fun screenLabel(state: UiState, reconfigureActive: Boolean): String {
    if (reconfigureActive) return "Reconfigure"
    return when (state) {
        is UiState.JoiningEvent -> "JoiningEvent:${state.phase::class.simpleName}"
        is UiState.Joined -> state.pendingSwitch
            ?.let { "Switch:${it.phase::class.simpleName}" }
            ?: "Joined"
        is UiState.CreateEvent -> "CreateEvent"
        UiState.CreatingEvent -> "CreatingEvent"
        else -> state::class.simpleName ?: "unknown"
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
    val current = currentEventName ?: "this event"
    when (val phase = switch.phase) {
        // Unreachable, and required for exhaustiveness. The explainer is chosen by the gate's loaded-phase
        // derivation only when NO event is configured — and while this dialog is up the previous event
        // still is. A switch does reach the explainer, but only AFTER its leave, by which point the state
        // is a full-screen `JoiningEvent` and not this overlay at all (capability `join-event`).
        is JoinPhase.ExplainAccess -> Unit
        is JoinPhase.Ready ->
            AppDestructiveConfirmDialog(
                title = "Switch events?",
                // The names carry the whole weight of the decision, so they are the whole body; the title
                // is the crisp question. Destructive, because the confirm leaves immediately. It promises
                // NO participation — the member picks direction, cutoff and album on the join surface that
                // follows — and shows no shareable count, there being no chosen range to count yet
                // (capability `join-share-count`).
                body = "You'll leave \"$current\" and join \"${phase.name}\".",
                confirmLabel = "Switch",
                cancelLabel = "Cancel",
                onConfirm = onConfirmSwitch,
                onDismiss = onCancelSwitch,
            )
        JoinPhase.NotFound ->
            AppConfirmDialog(
                title = "Invite not found",
                body = "This invite is invalid or the event no longer exists.",
                confirmLabel = "OK",
                cancelLabel = "Cancel",
                onConfirm = onCancelSwitch,
                onDismiss = onCancelSwitch,
            )
        JoinPhase.LoadFailed ->
            AppConfirmDialog(
                title = "Couldn't load the event",
                body = "Check your connection and try again.",
                confirmLabel = "Retry",
                cancelLabel = "Cancel",
                onConfirm = onRetryLoad,
                onDismiss = onCancelSwitch,
            )
        // Unreachable alongside `ExplainAccess`, and required for exhaustiveness: this dialog's confirm
        // runs only the leave, so no commit can fail while the previous event is still configured. A
        // post-leave commit failure is the full-screen surface's own retryable phase.
        is JoinPhase.CommitFailed -> Unit
        // Transient — no dialog while the details load or a commit runs.
        JoinPhase.Loading, is JoinPhase.Committing -> Unit
    }
}

/**
 * The three sentences this surface says differently from the join gate, derived from the persisted
 * membership rather than a phase.
 *
 * A plain function and not a composable: it reads nothing but its arguments, which is what makes the two
 * surfaces' divergence reviewable in one place instead of buried among layout.
 */
private fun reconfigureNotes(
    membership: EventConfig,
    selection: RangeSelection,
    saveToAlbum: Boolean,
) = ParticipationNotes(
    fromFloor = "Can't be earlier than the event started, ${appDateTimeLabel(selection.windowStart)}.",
    untilCeiling = if (membership.endsAt != null) {
        "Can't be later than the event ends, ${appDateTimeLabel(selection.windowEnd)}."
    } else {
        // A legacy membership whose event `endsAt` has not been backfilled yet: the picker still bounds
        // against the member's own ceiling, but naming an event end we do not know would be a guess.
        "Pick when to stop sharing."
    },
    // Forward-only (capability `reconfigure-membership`): already-synced photos are not retroactively
    // gathered, so the on-note says so plainly.
    album = if (saveToAlbum) {
        "Photos are collected in an album named after the event. Only photos synced " +
            "from now on are added."
    } else {
        "No album is created."
    },
)

/**
 * Save and Cancel, over the standing statement of what changing these settings does.
 *
 * That line used to say a change "never retracts photos already shared or received", and half of that
 * became false: narrowing what you share now re-projects the device manifest, so those photos stop being
 * listed to the event (capability `reconfigure-membership`).
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
        StatusHint(
            "Sharing less stops listing those photos to the event — anyone who already received " +
                "them keeps them. Photos you've received stay.",
        )
        if (!enabled) {
            StatusHint(
                "Turn on sharing or receiving — a membership that does neither does nothing.",
            )
        }
        PrimaryButton(label = "Save", onClick = onSave, enabled = enabled)
        SecondaryButton(label = "Cancel", onClick = onCancel)
    }
}
