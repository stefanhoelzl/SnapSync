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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.datetime.LocalDateTime

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
    ShareSection(state, actions, notes)
    ReceiveSection(state.receiveOn, actions.onReceiveOn)
    AlbumSection(state.saveToAlbum, actions.onSaveToAlbum, notes.album)
}

/**
 * **Do I share** — and if so, over which capture-date range.
 *
 * The only one of the three that is more than a switch and a sentence: the range rows refine THIS
 * switch, which is why they live inside its section rather than in a titled section of their own. A
 * third section would have implied a third question where there are only two.
 */
@Composable
private fun ColumnScope.ShareSection(
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
}

/** **Do I receive** — a switch and one sentence either way. */
@Composable
private fun ColumnScope.ReceiveSection(receiveOn: Boolean, onReceiveOn: (Boolean) -> Unit) {
AppToggleSection(
    title = "Receive everyone's photos",
    checked = receiveOn,
    onCheckedChange = onReceiveOn,
) {
    if (receiveOn) {
        AppSectionNote("Photos others share arrive in your library automatically.")
    } else {
        AppSectionNote("You won't receive the event's photos.")
    }
}

// The album (capability `event-album`) — a MINOR section: second-level checkmark idiom, standalone. It
// can nest under neither switch (the spec feeds the album from BOTH the member's own uploads and
// foreign downloads, so under Receive it was a false statement), but a switch section of its own gave
// a minor preference the same weight as a consent decision.
}

/** **Is an album created** — a minor preference, not a consent decision, so a second-level row. */
@Composable
private fun ColumnScope.AlbumSection(
    saveToAlbum: Boolean,
    onSaveToAlbum: (Boolean) -> Unit,
    note: String,
) {
AppMinorSection {
    AppSummaryToggle(
        label = "Create an album",
        checked = saveToAlbum,
        onCheckedChange = onSaveToAlbum,
        note = note,
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

/**
 * The seven pieces of state a member edits on a participation surface, owned in one place.
 *
 * Both surfaces held these as seven loose `var`s and then rebuilt the same [RangeChoices] and the same
 * [ParticipationActions] from them — about twenty lines each, differing only in where the initial values
 * came from. That is a state holder in the ordinary Compose sense, and having two copies of it meant the
 * two screens could drift apart in what they let a member change.
 *
 * Mutable and read during composition, so each field is `mutableStateOf`-backed: writing one recomposes
 * exactly the readers of that field.
 */
@Stable
internal class Participation(seed: ParticipationSeed) {
    var shareOn by mutableStateOf(seed.shareOn)
    var receiveOn by mutableStateOf(seed.receiveOn)
    var saveToAlbum by mutableStateOf(seed.saveToAlbum)
    var fromPreset by mutableStateOf(seed.choices.fromPreset)
    var fromCustom by mutableStateOf(seed.choices.fromCustom)
    var untilPreset by mutableStateOf(seed.choices.untilPreset)
    var untilCustom by mutableStateOf(seed.choices.untilCustom)

    /** The four range picks in the shape the design system's picker and the resolvers both take. */
    val choices: RangeChoices get() = RangeChoices(fromPreset, fromCustom, untilPreset, untilCustom)

    /** The edits, bound to this holder. [shareableCount] is the one thing it cannot supply itself. */
    fun actions(
        shareableCount: suspend (cutoff: CaptureCutoff, until: CaptureCeiling?) -> Int?,
    ) = ParticipationActions(
        choices = RangeChoiceActions(
            onFromPreset = { fromPreset = it },
            onFromCustom = { fromCustom = it },
            onUntilPreset = { untilPreset = it },
            onUntilCustom = { untilCustom = it },
        ),
        onShareOn = { shareOn = it },
        onReceiveOn = { receiveOn = it },
        onSaveToAlbum = { saveToAlbum = it },
        shareableCount = shareableCount,
    )

    /** What the surface displays, once the caller has resolved the range against its own window. */
    fun state(
        selection: RangeSelection,
        rangeLabel: String,
        photoPermission: PermissionStatus,
    ) = ParticipationState(
        selection = selection,
        choices = choices,
        rangeLabel = rangeLabel,
        shareOn = shareOn,
        receiveOn = receiveOn,
        saveToAlbum = saveToAlbum,
        photoPermission = photoPermission,
    )
}

/**
 * Where a [Participation] starts: all-on with the full event window at the join gate, and the persisted
 * membership's own values at the reconfigure surface.
 */
internal class ParticipationSeed(
    val shareOn: Boolean,
    val receiveOn: Boolean,
    val saveToAlbum: Boolean,
    val choices: RangeChoices,
)

/**
 * Survives the phase changes a surface goes through while mounted — Ready -> Committing -> CommitFailed
 * on the join gate — so a retry reuses what the member chose rather than re-seeding from defaults.
 */
@Composable
internal fun rememberParticipation(seed: ParticipationSeed): Participation =
    remember { Participation(seed) }

