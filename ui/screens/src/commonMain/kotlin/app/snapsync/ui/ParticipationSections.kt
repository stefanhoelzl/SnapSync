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
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.album_toggle
import app.snapsync.ui.resources.mobile_data_off_note
import app.snapsync.ui.resources.mobile_data_on_note
import app.snapsync.ui.resources.mobile_data_toggle
import app.snapsync.ui.resources.range_custom
import app.snapsync.ui.resources.range_from_now
import app.snapsync.ui.resources.range_whole_event
import app.snapsync.ui.resources.receive_off_note
import app.snapsync.ui.resources.receive_on_note
import app.snapsync.ui.resources.receive_toggle
import app.snapsync.ui.resources.share_detail_count
import app.snapsync.ui.resources.share_detail_counting
import app.snapsync.ui.resources.share_exclusions_note
import app.snapsync.ui.resources.share_off_note
import app.snapsync.ui.resources.share_toggle
import app.snapsync.ui.resources.share_zero_note
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource

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
        AppToggleRow(title = stringResource(Res.string.share_toggle), checked = state.shareOn, onCheckedChange = actions.onShareOn)
        ShareBody(state, actions)
        AppToggleDivider()
        // Titled to name the SOURCE ("everyone's photos"), not "save … to your library" — the latter reads as
        // backing up YOUR photos, the exact mental model this app must avoid.
        AppToggleRow(
            title = stringResource(Res.string.receive_toggle),
            checked = state.receiveOn,
            onCheckedChange = actions.onReceiveOn,
        )
        AppSectionNote(
            if (state.receiveOn) {
                stringResource(Res.string.receive_on_note)
            } else {
                stringResource(Res.string.receive_off_note)
            },
        )
    }
    AppToggleSection(
        title = stringResource(Res.string.album_toggle),
        checked = state.saveToAlbum,
        onCheckedChange = actions.onSaveToAlbum,
    ) {
        AppSectionNote(albumNote)
    }
    // Capability `mobile-data`: a preference over both directions, so its own card beneath the album; everything else
    // the app does keeps working on any network, which is why the note speaks of photos only.
    AppToggleSection(
        title = stringResource(Res.string.mobile_data_toggle),
        checked = state.mobileData,
        onCheckedChange = actions.onMobileData,
    ) {
        AppSectionNote(
            if (state.mobileData) {
                stringResource(Res.string.mobile_data_on_note)
            } else {
                stringResource(Res.string.mobile_data_off_note)
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
        AppSectionNote(stringResource(Res.string.share_off_note))
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
        AppSectionNote(stringResource(Res.string.share_zero_note))
    }
    // The origin exclusions (capability `photo-sharing`), stated as what is SUBTRACTED, never as a
    // guarantee of what gets through: the policy cannot infer capture-origin, so it removes only what is
    // certainly not a capture and ADMITS ON DOUBT. "Screenshots … are never shared" is exactly true; "only
    // photos you took are shared" would not be.
    AppSectionNote(stringResource(Res.string.share_exclusions_note))
}

/**
 * The range row's second line: which preset is chosen and, when a count is available, how many of the
 * member's own photos it would share (capability `join-event`). An unavailable count — no usable grant, or a
 * failed read — is omitted, not shown as zero: the two mean different things.
 */
@Composable
internal fun shareDetail(preset: RangeChoice, count: ShareCount): String {
    val name = stringResource(
        when (preset) {
            RangeChoice.WHOLE_EVENT -> Res.string.range_whole_event
            RangeChoice.FROM_NOW -> Res.string.range_from_now
            RangeChoice.CUSTOM -> Res.string.range_custom
        },
    )
    return when (count) {
        ShareCount.Counting -> stringResource(Res.string.share_detail_counting, name)
        ShareCount.Unavailable -> name
        is ShareCount.Ready -> pluralStringResource(Res.plurals.share_detail_count, count.count, name, count.count)
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
