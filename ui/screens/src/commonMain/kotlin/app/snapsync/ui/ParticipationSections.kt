package app.snapsync.ui

import app.snapsync.model.AlbumKind
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import app.snapsync.model.RangeChoice
import app.snapsync.model.RangeForm
import app.snapsync.model.ResolvedRange
import app.snapsync.model.ShareCount
import app.snapsync.ui.components.AppSectionNote
import app.snapsync.ui.components.AppShareRangeRow
import app.snapsync.ui.components.AppToggleCard
import app.snapsync.ui.components.AppToggleDivider
import app.snapsync.ui.components.AppToggleRow
import app.snapsync.ui.components.AppToggleSection
import app.snapsync.ui.components.RangeChoiceActions
import app.snapsync.ui.components.RangeChoices
import app.snapsync.ui.components.RangeWindow

// The participation decision surface (capabilities `join-event`, `manage-membership`,
// `photo-sharing`, `event-album`) — the three questions a member answers about an event, and the
// ONE place they are arranged.

/**
 * What a member decides about an event: **do I share** (and which photos), **do I receive**, **is an
 * album created**, and **may photos use mobile data** (capability `mobile-data`).
 *
 * The join gate and the in-place reconfigure surface ask exactly this, and both render it through here, so
 * the arrangement is written once and the two surfaces cannot drift apart.
 *
 * Share and Receive are two consent decisions about one membership, so they share ONE card, each switch
 * followed by what it means; the album is a preference that belongs to neither (capability `event-album`
 * feeds it from both), so it is its own switch card beneath. Which photos are shared is one row inside the
 * Share section — the range, "<preset> · <count>", and an edit affordance opening the calendar — because
 * "do I share" and "which photos" are one decision (decision record `simplify-join-screen`, D4).
 *
 * The only string the two callers genuinely say differently is the album note: at the join gate it states
 * what WILL be collected and varies over the switches; at reconfigure it states that turning the album on
 * also collects what the device already holds (capabilities `manage-membership`, `event-album`).
 */
@Composable
internal fun ColumnScope.ParticipationSections(
    state: ParticipationState,
    actions: ParticipationActions,
    albumNote: String,
) {
    AppToggleCard {
        AppToggleRow(title = "Share my photos", checked = state.shareOn, onCheckedChange = actions.onShareOn)
        ShareBody(state, actions)
        AppToggleDivider()
        // Titled to name the SOURCE ("everyone's photos"), not "save … to your library" — the latter reads as
        // backing up YOUR photos, the exact mental model this app must avoid.
        AppToggleRow(
            title = "Receive everyone's photos",
            checked = state.receiveOn,
            onCheckedChange = actions.onReceiveOn,
        )
        AppSectionNote(
            if (state.receiveOn) {
                "Photos others share arrive in your gallery on their own."
            } else {
                "You won't receive the event's photos."
            },
        )
    }
    AppToggleSection(
        title = "Create an album",
        checked = state.saveToAlbum,
        onCheckedChange = actions.onSaveToAlbum,
    ) {
        AppSectionNote(albumNote)
    }
    // Capability `mobile-data`: a preference over both directions, so its own card beneath the album; everything else
    // the app does keeps working on any network, which is why the note speaks of photos only.
    AppToggleSection(
        title = "Use mobile data for photos",
        checked = state.mobileData,
        onCheckedChange = actions.onMobileData,
    ) {
        AppSectionNote(
            if (state.mobileData) {
                "Photos are shared and received on any network."
            } else {
                "Photos are shared and received only on Wi-Fi."
            },
        )
    }
}

/**
 * What sharing means, beneath its switch: the range row, then what is never shared — or, with sharing off,
 * that nothing leaves the phone.
 */
@Composable
private fun ColumnScope.ShareBody(state: ParticipationState, actions: ParticipationActions) {
    if (!state.shareOn) {
        AppSectionNote("Nothing of yours leaves this phone.")
        return
    }
    AppShareRangeRow(
        choices = state.choices,
        actions = actions.choices,
        window = state.window,
        rangeLabel = state.rangeLabel,
        detail = shareDetail(state.form.preset, state.range.shareCount),
    )
    // A zero count carries a forward gloss so it does not read as broken (capability `join-event`).
    if (state.range.shareCount == ShareCount.Ready(0)) {
        AppSectionNote("New photos you take will be shared as you go.")
    }
    // The origin exclusions (capability `photo-sharing`), stated as what is SUBTRACTED, never as a
    // guarantee of what gets through: the policy cannot infer capture-origin, so it removes only what is
    // certainly not a capture and ADMITS ON DOUBT. "Screenshots … are never shared" is exactly true; "only
    // photos you took are shared" would not be.
    AppSectionNote("Screenshots, screen recordings, GIFs and photos saved from chat apps are never shared.")
}

/**
 * The range row's second line: which preset is chosen and, when a count is available, how many of the
 * member's own photos it would share (capability `join-event`). An unavailable count — no usable grant, or a
 * failed read — is omitted, not shown as zero: the two mean different things.
 */
internal fun shareDetail(preset: RangeChoice, count: ShareCount): String {
    val name = when (preset) {
        RangeChoice.WHOLE_EVENT -> "The whole event"
        RangeChoice.FROM_NOW -> "From now"
        RangeChoice.CUSTOM -> "Custom range"
    }
    return when (count) {
        ShareCount.Counting -> "$name · counting your photos…"
        ShareCount.Unavailable -> name
        is ShareCount.Ready -> "$name · ${count.count} ${if (count.count == 1) "photo" else "photos"} from your gallery"
    }
}

/**
 * What the participation surface displays — built from the reduced state, never from state the screen
 * holds. [form] is what the member has chosen; [range] is what that resolves to against the event window.
 */
class ParticipationState(
    val form: RangeForm,
    val range: ResolvedRange,
    /** The resolved range as one readable label — the surface's single statement of what will be shared. */
    val rangeLabel: String,
) {
    val shareOn: Boolean get() = form.shareOn
    val receiveOn: Boolean get() = form.receiveOn
    val saveToAlbum: Boolean get() = form.saveToAlbum
    val mobileData: Boolean get() = form.mobileData
    val albumKind: AlbumKind get() = form.albumKind
    val choices: RangeChoices get() = RangeChoices(form.preset, range.from, range.until)
    val window: RangeWindow get() = RangeWindow(range.windowStart, range.windowEnd, range.nowAvailable)
}

/** Everything the participation surface can ask for. */
class ParticipationActions(
    val choices: RangeChoiceActions,
    val onShareOn: (Boolean) -> Unit,
    val onReceiveOn: (Boolean) -> Unit,
    val onSaveToAlbum: (Boolean) -> Unit,
    val onMobileData: (Boolean) -> Unit,
)
