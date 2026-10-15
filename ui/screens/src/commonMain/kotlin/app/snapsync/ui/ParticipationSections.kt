package app.snapsync.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import app.snapsync.ui.components.AppSectionNote
import app.snapsync.ui.components.AppShareRangeRow
import app.snapsync.ui.components.AppToggleCard
import app.snapsync.ui.components.AppToggleDivider
import app.snapsync.ui.components.AppToggleRow
import app.snapsync.ui.components.RangeChoiceActions
import app.snapsync.ui.components.RangeChoices
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.album_toggle
import app.snapsync.ui.resources.receive_off_note
import app.snapsync.ui.resources.receive_on_note
import app.snapsync.ui.resources.receive_toggle
import app.snapsync.ui.resources.share_detail_count
import app.snapsync.ui.resources.share_detail_counting
import app.snapsync.ui.resources.share_exclusions_note
import app.snapsync.ui.resources.share_off_note
import app.snapsync.ui.resources.share_outside_after
import app.snapsync.ui.resources.share_outside_before
import app.snapsync.ui.resources.share_outside_both
import app.snapsync.ui.resources.share_selection_note
import app.snapsync.ui.resources.share_toggle
import app.snapsync.ui.resources.share_zero_note
import app.snapsync.ui.state.AlbumHolds
import app.snapsync.ui.state.OutsideReach
import app.snapsync.ui.state.RangeView
import app.snapsync.ui.state.ShareCount
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// The participation decision surface — the three questions a member answers about an event, and the
// ONE place they are arranged.

/**
 * What a member decides about an event: **do I share** (and which photos), **do I receive**, **is an
 * album created**, and **may photos use mobile data**.
 *
 * The join gate and the in-place reconfigure surface ask exactly this, and both render it through here, so
 * the arrangement is written once and the two surfaces cannot drift apart.
 *
 * Every choice sits in ONE card, each switch followed by what it means and set apart from the next by a line:
 * share, receive, the album (a preference fed by both directions), then mobile data —
 * one section reads as one decision about one membership. Which photos are shared sits inside the Share section — the
 * choices (from event start · from now · custom), the range they make and its count — because "do I share" and "which
 * photos" are one decision (decision records `simplify-join-screen`, D4; `flexible-share-range`, D3).
 *
 * The only string the two callers genuinely say differently is the album note: at the join gate it states
 * what WILL be collected and varies over the switches; at reconfigure it states that turning the album on
 * also collects what the device already holds.
 */
@Composable
internal fun ColumnScope.ParticipationSections(
    state: ParticipationState,
    actions: ParticipationActions,
    albumNote: String,
) {
    AppToggleCard {
        AppToggleRow(
            title = stringResource(Res.string.share_toggle),
            checked = state.shareOn,
            onCheckedChange = actions.onShareOn,
        )
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
        AppToggleDivider()
        AppToggleRow(
            title = stringResource(Res.string.album_toggle),
            checked = state.saveToAlbum,
            onCheckedChange = actions.onSaveToAlbum,
        )
        AppSectionNote(albumNote)
    }
}

/**
 * What sharing means, beneath its switch: the range row, a line when the range reaches outside the event's dates,
 * then what is never shared (or, under a partial grant, that the selected photos are) — or, with sharing off, that
 * nothing leaves the phone.
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
        fromNowOffered = state.range.nowAvailable,
        picker = state.range.picker,
        rangeLabel = state.rangeLabel,
        detail = shareDetail(state.range.shareCount)?.text(),
    )
    // A range reaching outside the event's dates says so, so it never passes for the event's own.
    state.range.outside?.let { AppSectionNote(stringResource(outsideNote(it))) }
    // A zero count carries a forward gloss so it does not read as broken.
    if (state.range.shareCount == ShareCount.Ready(0)) {
        AppSectionNote(stringResource(Res.string.share_zero_note))
    }
    // The origin exclusions, stated as what is SUBTRACTED, never as a
    // guarantee of what gets through: the policy cannot infer capture-origin, so it removes only what is
    // certainly not a capture and ADMITS ON DOUBT. "Screenshots … are never shared" is exactly true; "only
    // photos you took are shared" would not be. Under a partial grant none of them applies — the member picked each
    // photo — so the surface says what IS shared instead.
    val note = if (state.range.sharesSelection) Res.string.share_selection_note else Res.string.share_exclusions_note
    AppSectionNote(stringResource(note))
}

/**
 * The range row's second line: when a count is available, how many of the member's own photos the range would share.
 * An unavailable count — no usable grant, or a failed read — is no line at all, not zero: the two mean different
 * things. The chosen preset is not named here: the selected chip above says it.
 */
internal fun shareDetail(count: ShareCount): Phrase? = when (count) {
    ShareCount.Counting -> Phrase.Of(Res.string.share_detail_counting)
    ShareCount.Unavailable -> null
    is ShareCount.Ready -> Phrase.Counted(Res.plurals.share_detail_count, count.count, listOf(count.count))
}

/** The line that says which side of the event's dates the chosen range reaches past. */
internal fun outsideNote(outside: OutsideReach): StringResource = when (outside) {
    OutsideReach.BEFORE -> Res.string.share_outside_before
    OutsideReach.AFTER -> Res.string.share_outside_after
    OutsideReach.BOTH -> Res.string.share_outside_both
}

/**
 * What the participation surface displays — built from the reduced state, never from state the screen
 * holds: [range] is what the member has chosen and the range it makes against the event window.
 */
class ParticipationState(
    val range: RangeView,
    /** The resolved range as one readable label — the surface's single statement of what will be shared. */
    val rangeLabel: String,
) {
    val shareOn: Boolean get() = range.shareOn
    val receiveOn: Boolean get() = range.receiveOn
    val saveToAlbum: Boolean get() = range.saveToAlbum
    val albumHolds: AlbumHolds get() = range.albumHolds
    val choices: RangeChoices get() = RangeChoices(range.preset, range.from, range.until)
}

/** Everything the participation surface can ask for. */
class ParticipationActions(
    val choices: RangeChoiceActions,
    val onShareOn: (Boolean) -> Unit,
    val onReceiveOn: (Boolean) -> Unit,
    val onSaveToAlbum: (Boolean) -> Unit,
)
