package app.snapsync.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.PermissionStatus
import app.snapsync.ui.components.AppMinorSection
import app.snapsync.ui.components.AppRangePresetChoices
import app.snapsync.ui.components.AppSectionNote
import app.snapsync.ui.components.AppSectionValue
import app.snapsync.ui.components.AppSummaryToggle
import app.snapsync.ui.components.AppToggleSection
import app.snapsync.ui.components.FromChoice
import app.snapsync.ui.components.RangeChoiceActions
import app.snapsync.ui.components.RangeChoices
import app.snapsync.ui.components.UntilChoice

// The participation decision surface (capabilities `join-event`, `reconfigure-membership`,
// `photo-selection-policy`, `event-album`) — the three questions a member answers about an event, and the
// ONE place they are arranged.

/**
 * What a member decides about an event: **do I share** (and over which capture-date range), **do I
 * receive**, and **is an album created**.
 *
 * The join gate and the in-place reconfigure surface ask exactly this, and each used to spell the whole
 * arrangement out — the same two `AppToggleSection`s in the same order, the same four on/off notes, the
 * same value line, the same range picker, the same album row, with nine string literals identical between
 * them character for character. `ReconfigureScreen`'s own KDoc said the two "reuse the exact join controls
 * … so there is one decision surface", which was true of the COMPONENTS and false of their COMPOSITION:
 * the arrangement was the thing being duplicated, and it is what a reader has to compare to know the two
 * surfaces still agree.
 *
 * The parallel is exact with [RangeSelection] one layer down, where the same two surfaces were found
 * deriving the same ten values separately. This is that finding applied to the rendering.
 *
 * Only three strings genuinely differ between the callers, and they arrive as [notes] rather than being
 * unified away — see [ParticipationNotes] for why each is a real difference and not a copy.
 *
 * What stays with the caller is what is genuinely its own: the join gate's event header and retention
 * line, and the reconfigure surface's read-only title and Save/Cancel cluster.
 */
@Composable
internal fun ColumnScope.ParticipationSections(
    state: ParticipationState,
    actions: ParticipationActions,
    notes: ParticipationNotes,
) {
    // SHARE — the switch header, the exclusions, the resolved range in the heaviest type the surface
    // renders, the live count, and the range presets. The cutoff rows were briefly their own titled
    // section; that implied a third question where there are only two ("do I share" and "do I receive"),
    // so they folded back into the section whose switch they refine.
    AppToggleSection(
        title = "Share my photos",
        checked = state.shareOn,
        onCheckedChange = actions.onShareOn,
    ) {
        if (state.shareOn) {
            // The origin exclusions (capability `photo-selection-policy`), stated as what is SUBTRACTED,
            // never as a guarantee of what gets through: the policy cannot infer capture-origin (PhotoKit
            // exposes no camera flag), so it removes only what is certainly not a capture and ADMITS ON
            // DOUBT. "Screenshots … are never shared" is exactly true; "only photos you took are shared"
            // would not be.
            AppSectionNote(
                "Screenshots, screen recordings, GIFs and pictures saved from chat apps are " +
                    "never shared.",
            )
            // The ONE statement of the RANGE that decides which photos leave the phone. The Custom rows
            // below deliberately never repeat it — their pickers feed this line.
            AppSectionValue("Sharing ${state.rangeLabel}")
            // The live shareable count (capability `join-share-count`): how many of the member's own
            // gallery photos this RANGE would share, recomputed as either bound (or a late permission
            // resolve) changes. Omitted when no count is available.
            ShareCountRow(
                chosenCutoff = state.selection.chosenFrom,
                chosenUntil = state.selection.chosenUntil,
                shareableCount = actions.shareableCount,
                permissionKey = state.photoPermission,
            )
            // Level 2: the From/Until range presets, each its own captioned sub-list in its own recessed
            // well — the component owns those wells, so this section wraps it in none. Switch = does this
            // section happen; checkmarks = how.
            AppRangePresetChoices(
                choices = state.choices,
                // Only the picker's OK selects CUSTOM — a cancelled dialog leaves the previous choice
                // (and its instant) exactly as it was.
                actions = RangeChoiceActions(
                    onFromPreset = actions.choices.onFromPreset,
                    onFromCustom = {
                        actions.choices.onFromCustom(it)
                        actions.choices.onFromPreset(FromChoice.CUSTOM)
                    },
                    onUntilPreset = actions.choices.onUntilPreset,
                    onUntilCustom = {
                        actions.choices.onUntilCustom(it)
                        actions.choices.onUntilPreset(UntilChoice.CUSTOM)
                    },
                ),
                // Pre-start (and post-end), "Now" would fall outside the window — offered disabled.
                window = state.selection.window,
                fromFloorNote = notes.fromFloor,
                untilCeilingNote = notes.untilCeiling,
            )
        } else {
            AppSectionNote("Nothing of yours leaves this phone.")
        }
    }

    // RECEIVE. Titled to name the SOURCE ("everyone's photos"), not "save … to your library" — the latter
    // reads as backing up YOUR photos, the exact mental model this app must avoid, and breaks pronoun
    // parity with "Share my photos".
    AppToggleSection(
        title = "Receive everyone's photos",
        checked = state.receiveOn,
        onCheckedChange = actions.onReceiveOn,
    ) {
        if (state.receiveOn) {
            AppSectionNote("Photos others share arrive in your library automatically.")
        } else {
            AppSectionNote("You won't receive the event's photos.")
        }
    }

    // The album (capability `event-album`) — a MINOR section: second-level checkmark idiom, standalone. It
    // can nest under neither switch (the spec feeds the album from BOTH the member's own uploads and
    // foreign downloads, so under Receive it was a false statement), but a switch section of its own gave
    // a minor preference the same weight as a consent decision.
    AppMinorSection {
        AppSummaryToggle(
            label = "Create an album",
            checked = state.saveToAlbum,
            onCheckedChange = actions.onSaveToAlbum,
            note = notes.album,
            divider = false,
        )
    }
}

/** What the participation surface displays. */
internal class ParticipationState(
    val selection: RangeSelection,
    val choices: RangeChoices,
    /** The resolved range as one readable label — the section's single statement of what will be shared. */
    val rangeLabel: String,
    val shareOn: Boolean,
    val receiveOn: Boolean,
    val saveToAlbum: Boolean,
    /** A recompute trigger for the shareable count, not a rendered value. */
    val photoPermission: PermissionStatus,
)

/** Everything the participation surface can ask for. */
internal class ParticipationActions(
    val choices: RangeChoiceActions,
    val onShareOn: (Boolean) -> Unit,
    val onReceiveOn: (Boolean) -> Unit,
    val onSaveToAlbum: (Boolean) -> Unit,
    // The permission-aware count query over `[from, until]` (capability `join-share-count`).
    val shareableCount: suspend (cutoff: CaptureCutoff, until: CaptureCeiling?) -> Int?,
)

/**
 * The three sentences the two callers genuinely say differently. They are parameters and not shared text
 * because each difference is real, and collapsing any of them would make one surface lie:
 *
 * [fromFloor] and [untilCeiling] are the same sentences, but the reconfigure surface has a case the join
 * gate cannot have — a legacy membership whose event `endsAt` was never backfilled, where the picker still
 * bounds against the member's own ceiling but naming an event end nobody knows would be a guess.
 *
 * [album] differs in meaning, not wording. At the join gate it states what WILL be collected, and varies
 * over which switches are on; at reconfigure it states that the album is **forward-only** — already-synced
 * photos are not retroactively gathered (capability `reconfigure-membership`).
 */
internal class ParticipationNotes(
    val fromFloor: String,
    val untilCeiling: String,
    val album: String,
)
