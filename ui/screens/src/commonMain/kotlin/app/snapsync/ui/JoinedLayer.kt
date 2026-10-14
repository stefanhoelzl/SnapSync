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
import app.snapsync.ui.components.AccessPrompt
import app.snapsync.ui.components.AppDatesLine
import app.snapsync.ui.components.AppErrorBanner
import app.snapsync.ui.components.AppHeadingStatement
import app.snapsync.ui.components.AppOverStatement
import app.snapsync.ui.components.AppSectionDivider
import app.snapsync.ui.components.AppStatusDetail
import app.snapsync.ui.components.AppStatusLine
import app.snapsync.ui.components.AppSyncStatus
import app.snapsync.ui.components.LocalDateFormats
import app.snapsync.ui.components.appDateRangeLabel
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.counts_line
import app.snapsync.ui.resources.counts_line_members
import app.snapsync.ui.resources.counts_not_receiving
import app.snapsync.ui.resources.counts_not_sharing
import app.snapsync.ui.resources.counts_received
import app.snapsync.ui.resources.counts_received_progress
import app.snapsync.ui.resources.counts_shared
import app.snapsync.ui.resources.counts_shared_progress
import app.snapsync.ui.resources.event_over_body
import app.snapsync.ui.resources.event_over_title
import app.snapsync.ui.resources.everyone_shared
import app.snapsync.ui.resources.joinable_until
import app.snapsync.ui.resources.joined_statement
import app.snapsync.ui.resources.members_count
import app.snapsync.ui.resources.timing_ended
import app.snapsync.ui.resources.timing_ends_in
import app.snapsync.ui.resources.timing_starts_in
import app.snapsync.ui.resources.waiting_members
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.EventTiming
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.NetworkNotice
import app.snapsync.ui.state.SharingNote
import app.snapsync.ui.state.SyncCounts
import app.snapsync.ui.state.SyncHealth
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// The joined membership's own screen: the joined statement and the event's dates
// under its name, the status line with its counts, and the explanation of how the event works for this member.
// The invite and the membership actions are the docked footer [StatusScreen] hands the layout.

/**
 * The joined-layer event home: the one-line sync health first, because the screen is opened far more often to
 * check on photos than for anything else, then what the event means for this member ("How it works"). Only the
 * explanation scrolls: the status stays beneath the heading and the footer the layout docks beneath it stays
 * put, so on the smallest phone neither the sync health nor the invite and Leave are ever scrolled away.
 */
@Composable
internal fun JoinedLayer(
    state: Layer.Joined,
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
        if (state.deleted) {
            // Over for good: what it was is all there is left to say — no explanation of a sharing that has ended.
            OverBlock(state)
            return@Column
        }
        StatusBlock(state, access)
        // The line where the pinned status ends and the scrolling explanation begins.
        AppSectionDivider()
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Explanation(state, access, onOpenEventSettings)
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
                // A blocked network is SnapSync's own setting; an offline device is not.
                if (health == SyncHealth.NoNetwork(NetworkNotice.BLOCKED)) access.onOpenSettings()
            },
        )
        CountsLine(state.counts, state.members, state.sharingNote)
    }
}

/** A deleted event: the statement that it is over in place of the status line, and the counts as they last stood. */
@Composable
private fun OverBlock(state: Layer.Joined) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppOverStatement(stringResource(Res.string.event_over_title), stringResource(Res.string.event_over_body))
        CountsLine(state.counts, members = null, note = null)
    }
}

/**
 * What missing access asks for — the system's dialog the first time, the phone's Settings once refused. ONE
 * function for the status line and the explanation's link, so the two can never do different things.
 */
internal fun accessAction(health: SyncHealth.NeedsAccess, access: AccessActions): () -> Unit =
    if (health.canAsk) access.onRequestPermission else access.onOpenSettings

private fun SyncHealth.toAppSyncStatus(): AppSyncStatus = when (this) {
    is SyncHealth.NeedsAccess -> AppSyncStatus.NeedsAccess(if (canAsk) AccessPrompt.ALLOW else AccessPrompt.SETTINGS)
    is SyncHealth.NoNetwork -> AppSyncStatus.NoNetwork(blocked = notice == NetworkNotice.BLOCKED)
    SyncHealth.NotStarted -> AppSyncStatus.NotStarted
    SyncHealth.Inactive -> AppSyncStatus.Inactive
    SyncHealth.KeyLost -> AppSyncStatus.KeyLost
    is SyncHealth.Unattested -> AppSyncStatus.CannotVerifyDevice(refusal)
    is SyncHealth.ServiceFailing -> AppSyncStatus.ServiceFailing(notice)
    SyncHealth.Loading -> AppSyncStatus.Loading
    SyncHealth.InSync -> AppSyncStatus.InSync
    // Since the step-9 Arrow/ArrowLevel unification both sides speak `model/`'s Arrow — no mapping.
    is SyncHealth.Syncing -> AppSyncStatus.Syncing(upload, download, waitingForWifi, waitingForOthers)
}

/**
 * The counts line beneath the status line: how many members the event has, then per direction what went through.
 * The photo counts are absent whenever the reduction sent none — every status but "Up to date" and work in progress —
 * and the member count then stands alone. The ended event's note rides beneath it: it is only ever set under
 * "Up to date", where the counts are present too.
 */
@Composable
private fun CountsLine(counts: SyncCounts?, members: Int?, note: SharingNote?) {
    val people = members?.let { pluralStringResource(Res.plurals.members_count, it, it) }
    if (counts == null) {
        people?.let { AppStatusDetail(it) }
        return
    }
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
    AppStatusDetail(
        if (people == null) {
            Phrase.Of(Res.string.counts_line, listOf(shared, received)).text()
        } else {
            Phrase.Of(Res.string.counts_line_members, listOf(people, shared, received)).text()
        },
    )
    when (note) {
        is SharingNote.WaitingFor ->
            AppStatusDetail(pluralStringResource(Res.plurals.waiting_members, note.members, note.members))
        SharingNote.EveryoneShared -> AppStatusDetail(stringResource(Res.string.everyone_shared))
        null -> Unit
    }
}

/** `12/15 shared` while work remains, `15 shared` once complete, or what an off direction says. */
internal fun DirectionCount.label(done: StringResource, progress: StringResource, off: StringResource): Phrase =
    when (this) {
        DirectionCount.Off -> Phrase.Of(off)
        is DirectionCount.Progress ->
            if (complete) Phrase.Of(done, listOf(total)) else Phrase.Of(progress, listOf(this.done, total))
    }

/**
 * The lines beneath the joined event's name: that this device has joined — the
 * same words for the member who created the event and for everyone else, because the host is a member too and
 * the app does not record who created an event — and the event's dates with where it is in its life.
 */
@Composable
internal fun JoinedHeadingDetails(state: Layer.Joined) {
    // A deleted event is over: there is nothing to have joined any more, so it keeps only its dates.
    if (!state.deleted) AppHeadingStatement(stringResource(Res.string.joined_statement))
    val range = appDateRangeLabel(start = state.membership.startsAt, end = state.membership.endsAt, today = state.today)
    AppDatesLine(range, state.timing.phrase()?.text())
    state.joinableUntil?.let { until ->
        AppStatusDetail(stringResource(Res.string.joinable_until, LocalDateFormats.current.format(until, "MMMEd")))
    }
}

/** "starts in 2 days", "ends in 5 hours", "ended" — or nothing, for a membership with no stored end. */
internal fun EventTiming.phrase(): Phrase? = when (this) {
    is EventTiming.Upcoming -> Phrase.Of(Res.string.timing_starts_in, listOf(remaining.phrase()))
    is EventTiming.Running -> remaining?.let { Phrase.Of(Res.string.timing_ends_in, listOf(it.phrase())) }
    EventTiming.Ended -> Phrase.Of(Res.string.timing_ended)
}
