package app.snapsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.model.PermissionStatus
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.SyncHealth
import app.snapsync.ui.components.AppEyebrow
import app.snapsync.ui.components.EyebrowTone
import kotlinx.datetime.plus
import app.snapsync.ui.components.AppQrCode
import app.snapsync.ui.components.AccessPrompt
import app.snapsync.ui.components.AppStatusLine
import app.snapsync.ui.components.AppSyncStatus
import app.snapsync.ui.components.ScreenLayout
import app.snapsync.ui.components.SecondaryButton

// The joined membership's own screen (capability `sync-status-screen`): the QR to share, the sync
// health line, and the actions row.

/**
 * The joined-layer event home: the join QR is the hero, the one-line sync health beneath it (the event
 * name is the screen heading above, per [ScreenLayout]). The permission affordance is folded into the
 * status line (the `NeedsAccess` variant), tappable to the right action — never a hero-replacing gate.
 */
@Composable
internal fun JoinedLayer(
    health: SyncHealth,
    inviteUrl: String?,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    canChoosePhotos: Boolean,
    onChoosePhotos: () -> Unit,
    // The event's declared end has passed (capability `sync-status-screen`): prefix the health line with an
    // informational "Event ended" marker. Sync continues; the end is enforced only server-side.
    ended: Boolean,
    cutoff: CutoffFormatter,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        // The invite hero: sharing the event IS the point, so the QR is the tallest object on the screen.
        // A tracked accent eyebrow names what the code is FOR to the current member (share it to add
        // guests), while the card's own caption instructs the person scanning it — two audiences, one
        // statement each, so neither line repeats the other.
        if (inviteUrl != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppEyebrow("Share this event", EyebrowTone.Accent)
                AppQrCode(content = inviteUrl, caption = "Scan to join this event")
            }
        }
        // The one sync-health line — bare, no card. It briefly wore a surface-filled panel, but a white
        // card under a white QR card read as a second competing surface; the screen's second fixation
        // needs no frame, just position (centered, beneath the code).
        AppStatusLine(
            status = health.toAppSyncStatus(cutoff),
            ended = ended,
            onAttentionClick = {
                if (health is SyncHealth.NeedsAccess) {
                    if (health.permission == PermissionStatus.NOT_DETERMINED) {
                        onRequestPermission()
                    } else {
                        onOpenSettings()
                    }
                }
            },
        )
        // The partial-grant resting affordances (capability `limited-photo-access`): present in every
        // health, OUTSIDE the status-line slot — the selection is the membership's scope, and widening
        // it is an ordinary action, not a problem to fix. Two peer offers in fixed order: widen the
        // selection (the cheaper step) above, switch the grant itself below. The second can only
        // deep-link to Settings — no API re-raises the full-access dialog under a limited grant — and
        // deliberately carries no interstitial consent: the label plus the OS-mediated toggle are the
        // consent, and the widened scope stays bounded by the selection policy like any full grant.
        if (canChoosePhotos) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SecondaryButton(label = "Choose more photos", onClick = onChoosePhotos)
                SecondaryButton(label = "Allow full access", onClick = onOpenSettings)
            }
        }
    }
}

private fun SyncHealth.toAppSyncStatus(cutoff: CutoffFormatter): AppSyncStatus = when (this) {
    is SyncHealth.NeedsAccess -> AppSyncStatus.NeedsAccess(
        if (permission == PermissionStatus.NOT_DETERMINED) AccessPrompt.ALLOW else AccessPrompt.SETTINGS,
    )
    // The clock line renders the start in the DEVICE's local zone — a guest in another timezone sees the
    // event begin at their own wall-clock time, which is the honest reading of an instant. An unparseable
    // startsAt cannot occur (the details source normalizes it, and the config decoder requires it), so an
    // unreadable one degrades to the neutral first frame rather than crashing the joined screen.
    is SyncHealth.NotStarted ->
        cutoff.toLocal(startsAt.at)?.let { AppSyncStatus.NotStarted(it) } ?: AppSyncStatus.Loading
    SyncHealth.Unattested -> AppSyncStatus.CannotVerifyDevice
    SyncHealth.Loading -> AppSyncStatus.Loading
    SyncHealth.InSync -> AppSyncStatus.InSync
    // Since the step-9 Arrow/ArrowLevel unification both sides speak `model/`'s Arrow — no mapping.
    is SyncHealth.Syncing -> AppSyncStatus.Syncing(upload, download)
}
