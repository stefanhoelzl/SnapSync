package app.snapsync.ui

import app.snapsync.model.NetworkNotice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import app.snapsync.model.DirectionCount
import app.snapsync.model.EventTiming
import app.snapsync.model.MemberCounts
import app.snapsync.model.SyncCounts
import app.snapsync.model.TimeLeft
import app.snapsync.ui.components.appDateRangeLabel
import app.snapsync.ui.components.AppDatesLine
import app.snapsync.ui.components.AppHeadingStatement
import app.snapsync.ui.components.AppStatusDetail
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.model.GalleryAccess
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.model.SyncHealth
import app.snapsync.ui.components.AppErrorBanner
import app.snapsync.ui.components.AppEyebrow
import app.snapsync.ui.components.EyebrowTone
import app.snapsync.ui.components.AppQrCode
import app.snapsync.ui.components.AccessPrompt
import app.snapsync.ui.components.AppStatusLine
import app.snapsync.ui.components.AppSyncStatus
import app.snapsync.ui.components.ScreenLayout
import app.snapsync.ui.components.SecondaryButton
import app.snapsync.model.Layer
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.allow_full_access
import app.snapsync.ui.resources.choose_more_photos
import app.snapsync.ui.resources.counts_line
import app.snapsync.ui.resources.counts_not_receiving
import app.snapsync.ui.resources.counts_not_sharing
import app.snapsync.ui.resources.counts_received
import app.snapsync.ui.resources.counts_received_progress
import app.snapsync.ui.resources.counts_shared
import app.snapsync.ui.resources.counts_shared_progress
import app.snapsync.ui.resources.invite_caption
import app.snapsync.ui.resources.invite_eyebrow
import app.snapsync.ui.resources.joined_statement
import app.snapsync.ui.resources.timing_ended
import app.snapsync.ui.resources.timing_ends_in
import app.snapsync.ui.resources.timing_starts_in
import app.snapsync.ui.resources.waiting_members
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// The joined membership's own screen (capability `sync-status`): the joined statement and the event's
// dates under its name, the QR to invite others, the sync health line with its counts, and the actions row.

/**
 * The joined-layer event home: the join QR is the hero, the one-line sync health beneath it (the event
 * name is the screen heading above, per [ScreenLayout]). The permission affordance is folded into the
 * status line (the `NeedsAccess` variant), tappable to the right action — never a hero-replacing gate.
 */
@Composable
internal fun JoinedLayer(
    state: Layer.Joined,
    access: AccessActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        // A rejected event link, if one just arrived. Above the hero because it is about what the member
        // JUST DID, and it self-clears on its own; without it a bad scan while joined said nothing at all.
        state.notice?.let { AppErrorBanner(it.text()) }
        // The invite hero: sharing the event IS the point, so the QR is the tallest object on the screen.
        // BOTH lines — the tracked accent eyebrow and the card's caption — address the member holding the
        // device. There is no second audience: a person scanning the code is looking through their own
        // camera, not reading 14sp type on someone else's phone. The caption used to be written at that
        // scanner ("Scan to join this event"), which made it invisible to its intended reader and false to
        // its actual one — a member who is already joined, told to go scan something. One asked "what do I
        // need to do here?" in front of exactly that line.
        // So the caption may name NO noun the reader could be: "guests" fails as badly, because host and
        // guest see this identical screen and the confused member WAS a guest. Hence "others" — the people
        // the member invites. The eyebrow says what the code is FOR: it read "Share this event", which in a
        // photo-sharing app reads as sharing photos as readily as inviting people. Capability
        // `manage-membership`.
        // A closed event admits nobody, so it offers no invite (capability `manage-membership`).
        if (!state.closed) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppEyebrow(stringResource(Res.string.invite_eyebrow), EyebrowTone.Accent)
                // Rendered whenever the event is open: the joined state carries the invite URL non-null, so there is no
                // "joined but no link yet" frame for the hero to be missing in.
                AppQrCode(content = state.inviteUrl, caption = stringResource(Res.string.invite_caption))
            }
        }
        // The one sync-health line — bare, no card. It briefly wore a surface-filled panel, but a white
        // card under a white QR card read as a second competing surface; the screen's second fixation
        // needs no frame, just position (centered, beneath the code). The counts sit quietly beneath it.
        // Bound locally so the NeedsAccess branch below can smart-cast: `state.health` is a public
        // property of another module, which Kotlin will not narrow in place.
        val health = state.health
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppStatusLine(
                status = health.toAppSyncStatus(),
                onAttentionClick = {
                    if (health is SyncHealth.NeedsAccess) {
                        if (health.permission == GalleryAccess.NOT_DETERMINED) {
                            access.onRequestPermission()
                        } else {
                            access.onOpenSettings()
                        }
                    }
                    // A blocked network is SnapSync's own setting (capability `sync-status`); an offline device is not.
                    if (health is SyncHealth.NoNetwork && health.notice == NetworkNotice.BLOCKED) {
                        access.onOpenSettings()
                    }
                },
            )
            CountsLine(state.counts, state.waiting)
        }
        // The partial-grant resting affordances (capability `photo-access`): present in every
        // health, OUTSIDE the status-line slot — the selection is the membership's scope, and widening
        // it is an ordinary action, not a problem to fix. Two peer offers in fixed order: widen the
        // selection (the cheaper step) above, switch the grant itself below. The second can only
        // deep-link to Settings — no API re-raises the full-access dialog under a limited grant — and
        // deliberately carries no interstitial consent: the label plus the OS-mediated toggle are the
        // consent, and the widened scope stays bounded by the selection policy like any full grant.
        if (state.canChoosePhotos) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SecondaryButton(label = stringResource(Res.string.choose_more_photos), onClick = access.onChoosePhotos)
                SecondaryButton(label = stringResource(Res.string.allow_full_access), onClick = access.onOpenSettings)
            }
        }
    }
}

private fun SyncHealth.toAppSyncStatus(): AppSyncStatus = when (this) {
    is SyncHealth.NeedsAccess -> AppSyncStatus.NeedsAccess(
        if (permission == GalleryAccess.NOT_DETERMINED) AccessPrompt.ALLOW else AccessPrompt.SETTINGS,
    )
    is SyncHealth.NoNetwork -> AppSyncStatus.NoNetwork(blocked = notice == NetworkNotice.BLOCKED)
    SyncHealth.NotStarted -> AppSyncStatus.NotStarted
    SyncHealth.Unattested -> AppSyncStatus.CannotVerifyDevice
    SyncHealth.Loading -> AppSyncStatus.Loading
    SyncHealth.InSync -> AppSyncStatus.InSync
    // Since the step-9 Arrow/ArrowLevel unification both sides speak `model/`'s Arrow — no mapping.
    is SyncHealth.Syncing -> AppSyncStatus.Syncing(upload, download, waitingForWifi)
}

/**
 * The counts line beneath the status line (capability `sync-status`): per direction, what went through.
 * Absent whenever the reduction sent no counts — every status but "In sync" and syncing. The ended event's
 * waiting note rides beneath it: it is only ever set under "In sync", where the counts are present too.
 */
@Composable
private fun CountsLine(counts: SyncCounts?, waiting: MemberCounts?) {
    if (counts == null) return
    val shared = counts.shared.label(Res.string.counts_shared, Res.string.counts_shared_progress, Res.string.counts_not_sharing)
    val received = counts.received.label(
        Res.string.counts_received,
        Res.string.counts_received_progress,
        Res.string.counts_not_receiving,
    )
    AppStatusDetail(stringResource(Res.string.counts_line, shared, received))
    waiting?.let { AppStatusDetail(stringResource(Res.string.waiting_members, it.waitingFor, it.active)) }
}

/** `12/15 shared` while work remains, `15 shared` once complete, or what an off direction says. */
@Composable
private fun DirectionCount.label(done: StringResource, progress: StringResource, off: StringResource): String =
    when (this) {
        DirectionCount.Off -> stringResource(off)
        is DirectionCount.Progress ->
            if (complete) stringResource(done, total) else stringResource(progress, this.done, total)
    }

/**
 * The lines beneath the joined event's name (capability `sync-status`): that this device has joined — the
 * same words for the member who created the event and for everyone else, because the host is a member too and
 * the app does not record who created an event — and the event's dates with where it is in its life.
 */
@Composable
internal fun JoinedHeadingDetails(state: Layer.Joined, cutoff: CutoffFormatter) {
    AppHeadingStatement(stringResource(Res.string.joined_statement))
    // An unparseable start cannot occur (the config decoder requires it); a line that cannot be drawn is
    // left out rather than guessed.
    val start = cutoff.toLocal(state.membership.startsAt.at) ?: return
    val range = appDateRangeLabel(
        start = start,
        end = state.membership.endsAt?.let { cutoff.toLocal(it.at) },
        today = cutoff.nowLocal().date,
    )
    AppDatesLine(range, state.timing.phrase())
}

/** "starts in 2 days", "ends in 5 hours", "ended" — or nothing, for a membership with no stored end. */
@Composable
private fun EventTiming.phrase(): String? = when (this) {
    is EventTiming.Upcoming -> stringResource(Res.string.timing_starts_in, remaining.text())
    is EventTiming.Running -> remaining?.let { stringResource(Res.string.timing_ends_in, it.text()) }
    EventTiming.Ended -> stringResource(Res.string.timing_ended)
}
