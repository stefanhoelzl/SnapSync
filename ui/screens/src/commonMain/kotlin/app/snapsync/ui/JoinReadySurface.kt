package app.snapsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.PermissionStatus
import app.snapsync.ui.components.AppEventHeaderCompact
import app.snapsync.ui.components.AppMinorSection
import app.snapsync.ui.components.AppRangePresetChoices
import app.snapsync.ui.components.AppSectionNote
import app.snapsync.ui.components.AppSectionValue
import app.snapsync.ui.components.AppSummaryToggle
import app.snapsync.ui.components.AppToggleSection
import app.snapsync.presentation.ResolvedRange
import app.snapsync.model.FromChoice
import app.snapsync.ui.components.PrimaryButton
import app.snapsync.ui.components.SecondaryButton
import app.snapsync.ui.components.StatusHint
import app.snapsync.model.UntilChoice
import kotlinx.datetime.LocalDateTime
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.snapsync.ui.components.RangeChoiceActions
import app.snapsync.ui.components.RangeChoices

// The **Ready** join surface (capability `join-event`): the decision the guest actually makes, and the
// shareable-count row it shares with the reconfigure screen. Split out of `JoinFlowScreens.kt` because
// that file now holds the OTHER join shape — the status-plus-actions phases and the scaffold they opt
// into — and Ready is the one phase that declines it.

/**
 * The **Ready** join surface: identity, then two stacked sections that each state, in words, one
 * consequence of joining — and Join / Cancel pinned at the bottom.
 *
 * The surface no longer asks "how do you want to take part?" and no longer offers a direction selector.
 * The two things a guest actually decides are stated as plain on/off switches — **Share my photos** and
 * **Receive everyone's photos** — and the participation *direction* is DERIVED from them
 * (share+receive → Both, share only → upload-only, receive only → download-only). There is deliberately no
 * "no photos" option: not sharing is the share switch off, not receiving is the receive switch off.
 *
 * The two sections, top to bottom:
 *  1. **Share** ([AppToggleSection]) — the switch, the origin-exclusions note (what the app already
 *     filters out of a camera roll — new information no other state of this screen carries), the resulting
 *     range in the heaviest type on the surface, and the range choice rows ([AppRangePresetChoices]) as two
 *     captioned sub-lists — **Share from** (Event start / Now / Custom) and **Share until** (Event end /
 *     Custom), each in its own recessed well the component owns — all in one card, because "do I share" and
 *     "from when / until when" are one decision. Custom opens the window-constrained date+time picker
 *     directly; only its OK commits the choice, and the chosen instants appear solely in the bold
 *     "Sharing …" line (never repeated in a row). When off, the card states that nothing of theirs leaves
 *     the phone and the rows are not shown.
 *  2. **Receive** ([AppToggleSection]) — the switch and where arriving photos land.
 *  3. **Album** ([AppMinorSection] + [AppSummaryToggle]) — a standalone second-level checkmark row: per
 *     capability `event-album` the album mirrors what the membership syncs in its direction — foreign
 *     downloads and/or the member's OWN uploads — so it belongs to neither switch, but it ranks below
 *     both (a preference, not a consent decision). Its note names exactly the feeds the current
 *     switches produce.
 *
 * Reading order is causal: who invited me → what I share (and from when) → what I receive (and where).
 *
 * Both switches off is a membership that does nothing. Rather than silently flip one switch the guest did
 * not touch, Join is **disabled** with the reason stated right above it.
 *
 * The body scrolls beneath the pinned actions: its height is not fixed (the cutoff section appears and
 * disappears, and Custom unfolds a picker), and clipping the primary action is never an acceptable way to
 * absorb that.
 */
@Composable
internal fun ReadyLayout(state: ReadyState, actions: ReadyActions) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppEventHeaderCompact(
                title = state.eventName,
                // The one warm line the surface allows itself — the eyebrow above already says
                // "you're invited", so this states what the invitation IS.
                subtitle = "Everyone's photos, one shared place.",
            )

            ParticipationSections(
                state = state.participation,
                actions = actions.participation,
                notes = ParticipationNotes(
                    fromFloor = "Can't be earlier than the event started, ${state.labels.floor}.",
                    untilCeiling = "Can't be later than the event ends, ${state.labels.ceiling}.",
                    // What WILL be collected, named exactly for the switches currently on, so the row can
                    // never claim a feed the membership does not have.
                    album = with(state.participation) {
                        when {
                            !saveToAlbum -> "No album is created."
                            shareOn && receiveOn ->
                                "Photos you share and photos you receive are collected in an album " +
                                    "named after the event."
                            shareOn -> "Photos you share are collected in an album named after the event."
                            receiveOn ->
                                "Photos you receive are collected in an album named after the event."
                            // Both switches off: nothing syncs, so nothing feeds the album. Join is already
                            // disabled with its own reason; this line keeps the row honest meanwhile.
                            else -> "Nothing is shared or received, so nothing is collected."
                        }
                    },
                ),
            )

            // How long the shared photos are kept (capability `event-limits`). This is the ONE place the
            // app states retention — the creator passes through this same gate right after minting, so a
            // single line serves the host and every guest.
            //
            // The date is the CEILING, stated unconditionally. An event is often reclaimed sooner (once
            // everyone has left, capability `scheduled-cleanup`), but that depends on every member's leave
            // reaching the backend and is NOT assured — so it must never be presented as a promise, nor as
            // a qualification that makes this date read as unreliable.
            AppMinorSection {
                AppSectionNote(
                    buildString {
                        state.labels.deletes?.let { append("Shared photos are deleted on $it. ") }
                        append("An event's photos are kept for at most 30 days from the day it starts.")
                    },
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Both switches off is a membership that does nothing. Say why Join is unavailable rather than
            // moving a switch the guest didn't touch.
            if (!state.range.commitEnabled) {
                StatusHint(
                    "Turn on sharing or receiving — a membership that does neither does nothing.",
                )
            }
            PrimaryButton(label = "Join", onClick = actions.onJoin, enabled = state.range.commitEnabled)
            SecondaryButton(label = "Cancel", onClick = actions.onCancel)
        }
    }
}

/** The live state of the shareable-count row (capability `join-share-count`). */
private sealed interface CountState {
    /** The count is being (re)computed — the row shows `counting…`. */
    data object Counting : CountState

    /** No count is available (DENIED / unresolved grant) — the row is omitted entirely. */
    data object Unavailable : CountState

    /** The count resolved to [count] photos. */
    data class Ready(val count: Int) : CountState
}

/**
 * The shareable-count row (capability `join-share-count`): `XX photos from your gallery will be shared`,
 * recomputed whenever the resolved [chosenCutoff] changes (the member tunes the cutoff) or [permissionKey]
 * flips (a late first-join grant resolves). A brief `counting…` shows while it recomputes; a zero carries a
 * forward gloss so it does not read as broken; an unavailable count (no usable grant) renders **nothing**.
 *
 * Shared by the join, switch, and reconfigure surfaces. It is a rendering concern living entirely in the
 * screen — [shareableCount] is the permission-aware, no-network query built in `compose/`.
 */
@Composable
internal fun ShareCountRow(
    chosenCutoff: CaptureCutoff,
    chosenUntil: CaptureCeiling?,
    shareableCount: suspend (cutoff: CaptureCutoff, until: CaptureCeiling?) -> Int?,
    permissionKey: PermissionStatus,
) {
    var state by remember { mutableStateOf<CountState>(CountState.Counting) }
    LaunchedEffect(chosenCutoff, chosenUntil, permissionKey) {
        state = CountState.Counting
        val n = shareableCount(chosenCutoff, chosenUntil)
        state = if (n == null) CountState.Unavailable else CountState.Ready(n)
    }
    when (val s = state) {
        CountState.Counting -> AppSectionNote("Counting your photos…")
        CountState.Unavailable -> Unit // no row without a usable photo grant
        is CountState.Ready -> {
            val noun = if (s.count == 1) "photo" else "photos"
            AppSectionNote("${s.count} $noun from your gallery will be shared")
            if (s.count == 0) {
                AppSectionNote("New photos you take will be shared as you go")
            }
        }
    }
}

/**
 * What the **Ready** join surface displays. Two parameters replace twenty-nine: the previous signature
 * interleaved each value with its own callback, pair by pair, which is the shape that grows without bound.
 *
 * Most of this is not new state — [participation] carries the reduced form and its resolution. What was
 * genuinely loose is [labels], the strings the design system renders the window as.
 */
internal class ReadyState(
    val eventName: String,
    val participation: ParticipationState,
    val labels: ReadyLabels,
) {
    /** The join button is enabled on the same rule the reduction commits on. */
    val range: ResolvedRange get() = participation.range
}

/**
 * The four pre-formatted strings the surface states, kept together because they are all derived from the
 * same window by the caller that owns the formatter.
 *
 * [deletes] is `null` only when the phase carries no retention deadline, in which case the section states
 * the fixed ceiling alone rather than inventing a date.
 */
internal class ReadyLabels(
    val floor: String,
    val ceiling: String,
    val deletes: String?,
)

/**
 * Everything the Ready surface can ask for: the shared participation surface's actions, plus the two this
 * surface adds. Three fields for eleven callbacks, which is the nesting argument at its limit — bundling
 * recurses forever only if each bundle is FLAT; a bundle of bundles has as many fields as it has GROUPS.
 */
internal class ReadyActions(
    val participation: ParticipationActions,
    val onJoin: () -> Unit,
    val onCancel: () -> Unit,
)
