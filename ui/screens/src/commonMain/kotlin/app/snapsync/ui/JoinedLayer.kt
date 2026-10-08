package app.snapsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.model.DirectionCount
import app.snapsync.model.EventTiming
import app.snapsync.model.GalleryAccess
import app.snapsync.model.Layer
import app.snapsync.model.MemberCounts
import app.snapsync.model.NetworkNotice
import app.snapsync.model.SyncCounts
import app.snapsync.model.SyncHealth
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.ui.components.AccessPrompt
import app.snapsync.ui.components.AppDatesLine
import app.snapsync.ui.components.AppErrorBanner
import app.snapsync.ui.components.AppHeadingStatement
import app.snapsync.ui.components.AppSectionDivider
import app.snapsync.ui.components.AppStatusDetail
import app.snapsync.ui.components.AppStatusLine
import app.snapsync.ui.components.AppSyncStatus
import app.snapsync.ui.components.appDateRangeLabel
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.counts_line
import app.snapsync.ui.resources.counts_not_receiving
import app.snapsync.ui.resources.counts_not_sharing
import app.snapsync.ui.resources.counts_received
import app.snapsync.ui.resources.counts_received_progress
import app.snapsync.ui.resources.counts_shared
import app.snapsync.ui.resources.counts_shared_progress
import app.snapsync.ui.resources.joined_statement
import app.snapsync.ui.resources.timing_ended
import app.snapsync.ui.resources.timing_ends_in
import app.snapsync.ui.resources.timing_starts_in
import app.snapsync.ui.resources.waiting_members
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// The joined membership's own screen (capability `sync-status`): the joined statement and the event's dates
// under its name, the status line with its counts, and the explanation of how the event works for this member.
// The invite and the membership actions are the docked footer [StatusScreen] hands the layout.

/**
 * The joined-layer event home: the one-line sync health first, because the screen is opened far more often to
 * check on photos than for anything else, then what the event means for this member ("How it works"). Only the
 * explanation scrolls: the status stays beneath the heading and the footer the layout docks beneath it stays
 * put, so on the smallest phone neither the sync health nor the invite and Leave are ever scrolled away
 * (capability `sync-status`).
 */
@Composable
internal fun JoinedLayer(
    state: Layer.Joined,
    cutoff: CutoffFormatter,
    access: AccessActions,
    onOpenEventSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        // A rejected event link, if one just arrived. First because it is about what the member JUST DID, and it
        // self-clears on its own; without it a bad scan while joined said nothing at all.
        state.notice?.let { AppErrorBanner(it.text()) }
        StatusBlock(state, access)
        // The line where the pinned status ends and the scrolling explanation begins.
        AppSectionDivider()
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Explanation(state, cutoff, access, onOpenEventSettings)
            Spacer(Modifier.height(SECTION_GAP))
        }
    }
}

/** The space between the joined layer's sections, and beneath the explanation's last row. */
private val SECTION_GAP = 28.dp

/**
 * The one sync-health line — bare, no card — with the counts quietly beneath it. The permission affordance is
 * folded into the line (the `NeedsAccess` variant), tappable to the right action.
 */
@Composable
private fun StatusBlock(state: Layer.Joined, access: AccessActions) {
    // Bound locally so the branches below can smart-cast: `state.health` is a public property of another
    // module, which Kotlin will not narrow in place.
    val health = state.health
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppStatusLine(
            status = health.toAppSyncStatus(),
            onAttentionClick = {
                if (health is SyncHealth.NeedsAccess) accessAction(health, access)()
                // A blocked network is SnapSync's own setting (capability `sync-status`); an offline device is not.
                if (health == SyncHealth.NoNetwork(NetworkNotice.BLOCKED)) access.onOpenSettings()
            },
        )
        CountsLine(state.counts, state.waiting)
    }
}

/**
 * What missing access asks for — the system's dialog the first time, the phone's Settings once refused. ONE
 * function for the status line and the explanation's link, so the two can never do different things
 * (capability `photo-access`).
 */
internal fun accessAction(health: SyncHealth.NeedsAccess, access: AccessActions): () -> Unit =
    if (health.permission == GalleryAccess.NOT_DETERMINED) access.onRequestPermission else access.onOpenSettings

private fun SyncHealth.toAppSyncStatus(): AppSyncStatus = when (this) {
    is SyncHealth.NeedsAccess -> AppSyncStatus.NeedsAccess(
        if (permission == GalleryAccess.NOT_DETERMINED) AccessPrompt.ALLOW else AccessPrompt.SETTINGS,
    )
    is SyncHealth.NoNetwork -> AppSyncStatus.NoNetwork(blocked = notice == NetworkNotice.BLOCKED)
    SyncHealth.NotStarted -> AppSyncStatus.NotStarted
    SyncHealth.Inactive -> AppSyncStatus.Inactive
    SyncHealth.KeyLost -> AppSyncStatus.KeyLost
    is SyncHealth.Unattested -> AppSyncStatus.CannotVerifyDevice(refusal)
    SyncHealth.Loading -> AppSyncStatus.Loading
    SyncHealth.InSync -> AppSyncStatus.InSync
    // Since the step-9 Arrow/ArrowLevel unification both sides speak `model/`'s Arrow — no mapping.
    is SyncHealth.Syncing -> AppSyncStatus.Syncing(upload, download, waitingForWifi)
}

/**
 * The counts line beneath the status line (capability `sync-status`): per direction, what went through.
 * Absent whenever the reduction sent no counts — every status but "Up to date" and work in progress. The ended event's
 * waiting note rides beneath it: it is only ever set under "Up to date", where the counts are present too.
 */
@Composable
private fun CountsLine(counts: SyncCounts?, waiting: MemberCounts?) {
    if (counts == null) return
    val shared = counts.shared.label(
        Res.string.counts_shared,
        Res.string.counts_shared_progress,
        Res.string.counts_not_sharing,
    )
    val received = counts.received.label(
        Res.string.counts_received,
        Res.string.counts_received_progress,
        Res.string.counts_not_receiving,
    )
    AppStatusDetail(Phrase.Of(Res.string.counts_line, listOf(shared, received)).text())
    waiting?.let { AppStatusDetail(stringResource(Res.string.waiting_members, it.waitingFor, it.active)) }
}

/** `12/15 shared` while work remains, `15 shared` once complete, or what an off direction says. */
internal fun DirectionCount.label(done: StringResource, progress: StringResource, off: StringResource): Phrase =
    when (this) {
        DirectionCount.Off -> Phrase.Of(off)
        is DirectionCount.Progress ->
            if (complete) Phrase.Of(done, listOf(total)) else Phrase.Of(progress, listOf(this.done, total))
    }

/**
 * The lines beneath the joined event's name (capability `sync-status`): that this device has joined — the
 * same words for the member who created the event and for everyone else, because the host is a member too and
 * the app does not record who created an event — and the event's dates with where it is in its life.
 */
@Composable
internal fun JoinedHeadingDetails(state: Layer.Joined, cutoff: CutoffFormatter) {
    AppHeadingStatement(stringResource(Res.string.joined_statement))
    val start = cutoff.toLocal(state.membership.startsAt)
    val range = appDateRangeLabel(
        start = start,
        end = cutoff.toLocal(state.membership.endsAt),
        today = cutoff.nowLocal().date,
    )
    AppDatesLine(range, state.timing.phrase()?.text())
}

/** "starts in 2 days", "ends in 5 hours", "ended" — or nothing, for a membership with no stored end. */
internal fun EventTiming.phrase(): Phrase? = when (this) {
    is EventTiming.Upcoming -> Phrase.Of(Res.string.timing_starts_in, listOf(remaining.phrase()))
    is EventTiming.Running -> remaining?.let { Phrase.Of(Res.string.timing_ends_in, listOf(it.phrase())) }
    EventTiming.Ended -> Phrase.Of(Res.string.timing_ended)
}
