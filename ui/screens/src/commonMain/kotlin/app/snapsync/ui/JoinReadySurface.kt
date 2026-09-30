package app.snapsync.ui

import app.snapsync.model.AlbumKind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.model.ResolvedRange
import app.snapsync.ui.components.AppAccessNotice
import app.snapsync.ui.components.AppAccessPoint
import app.snapsync.ui.components.AppEventHeaderCompact
import app.snapsync.ui.components.JoinAccessChoose
import app.snapsync.ui.components.JoinAccessCutoff
import app.snapsync.ui.components.JoinAccessLibrary
import app.snapsync.ui.components.JoinAccessShare
import app.snapsync.ui.components.PrimaryButton
import app.snapsync.ui.components.SecondaryButton
import app.snapsync.ui.components.StatusHint

// The **Ready** join surface (capability `join-event`): the decision the guest actually makes. Split out of
// `JoinFlowScreens.kt` because that file holds the OTHER join shape — the status-plus-actions phases and the
// scaffold they opt into — and Ready is the one phase that declines it.

/**
 * The **Ready** join surface: identity, the participation choices ([ParticipationSections]), and Join /
 * Cancel pinned at the bottom. At the default state the whole decision fits one phone screen (decision record
 * `simplify-join-screen`).
 *
 * **Photo access is part of this surface, not a step before it** (capabilities `join-event`,
 * `photo-access`). For a guest iOS has never asked ([ReadyState.asksAccessOnJoin]) a one-line notice above
 * the confirm says iOS asks next, its ⓘ opens the explanation as a sheet that raises nothing, and the confirm
 * reads "Join & allow photos" — tapping it is the deliberate action that raises iOS's dialog, and the join
 * goes ahead whatever the answer. Everyone else sees plain "Join" and no notice.
 *
 * Both switches off is a membership that does nothing. Rather than silently flip one switch the guest did
 * not touch, Join is **disabled** with the reason stated right above it.
 *
 * The body scrolls beneath the pinned actions: its height is not fixed (sharing off, a zero count, a large
 * type size), and clipping the primary action is never an acceptable way to absorb that.
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
                albumNote = joinAlbumNote(state.participation),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.asksAccessOnJoin) {
                AppAccessNotice(
                    text = "iOS asks for access to your photos next",
                    infoDescription = "What joining does with your photos",
                    sheetTitle = "What joining does",
                    dismissLabel = "Got it",
                    explanation = { AccessExplanation() },
                )
            }
            // Both switches off is a membership that does nothing. Say why Join is unavailable rather than
            // moving a switch the guest didn't touch.
            if (!state.range.commitEnabled) {
                StatusHint("Turn on sharing or receiving — a membership that does neither does nothing.")
            }
            PrimaryButton(
                label = if (state.asksAccessOnJoin) "Join & allow photos" else "Join",
                onClick = actions.onJoin,
                enabled = state.range.commitEnabled,
            )
            SecondaryButton(label = "Cancel", onClick = actions.onCancel)
        }
    }
}

/**
 * The photo-access explanation, on request (capability `join-event`): share-first (the automatic sharing is
 * the half that deserves informed consent, so it leads), then that the library is needed for BOTH halves,
 * then that picking specific photos is a first-class choice (capability `photo-access`), then the range.
 * Reading it raises nothing — closing is its only action.
 */
@Composable
private fun AccessExplanation() {
    AppAccessPoint(
        icon = JoinAccessShare,
        title = "Your photos are shared automatically",
        body = "The photos you take show up for everyone in the event.",
        divider = false,
    )
    AppAccessPoint(
        icon = JoinAccessLibrary,
        title = "SnapSync needs your photo library",
        body = "To share yours, and to save the photos other members send you.",
    )
    AppAccessPoint(
        icon = JoinAccessChoose,
        title = "Allow all photos, or pick which to share",
        body = "Choosing specific photos works too — and you can add more anytime.",
    )
    AppAccessPoint(
        icon = JoinAccessCutoff,
        title = "Only photos in the range you chose",
        body = "Nothing older is shared.",
    )
}

/**
 * What the album will collect, named exactly for the switches currently on, so the row can never claim a feed
 * the membership does not have (capability `event-album`). A folder album (Android) holds only what is received:
 * the member's own photos stay where their camera saved them, and the note says so.
 */
private fun joinAlbumNote(participation: ParticipationState): String = with(participation) {
    when {
        !saveToAlbum -> "No album is created."
        albumKind == AlbumKind.FOLDER && receiveOn ->
            "Photos you receive are collected in an album named after the event. " +
                "Your own photos stay in your camera folder."
        albumKind == AlbumKind.FOLDER -> "You won't receive photos, so nothing is collected."
        shareOn && receiveOn ->
            "Photos you share and photos you receive are collected in an album named after the event."
        shareOn -> "Photos you share are collected in an album named after the event."
        receiveOn -> "Photos you receive are collected in an album named after the event."
        // Both switches off: nothing syncs, so nothing feeds the album. Join is already disabled with its own
        // reason; this line keeps the row honest meanwhile.
        else -> "Nothing is shared or received, so nothing is collected."
    }
}

/** What the **Ready** join surface displays. */
internal class ReadyState(
    val eventName: String,
    val participation: ParticipationState,
    /** Confirming also raises iOS's photo-access dialog — see [ReadyLayout]. */
    val asksAccessOnJoin: Boolean,
) {
    /** The join button is enabled on the same rule the reduction commits on. */
    val range: ResolvedRange get() = participation.range
}

/**
 * Everything the Ready surface can ask for: the shared participation surface's actions, plus the two this
 * surface adds.
 */
internal class ReadyActions(
    val participation: ParticipationActions,
    val onJoin: () -> Unit,
    val onCancel: () -> Unit,
)
