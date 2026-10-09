package app.snapsync.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.snapsync.ui.components.resources.Res
import app.snapsync.ui.components.resources.invited_eyebrow
import org.jetbrains.compose.resources.stringResource

/**
 * The join gate's non-Ready phases all share one design language with the Ready surface
 * (`AppEventHeaderCompact`, the bordered cards): identity leads, consequences are stated in words, and the
 * palette stays frozen. This file holds the three pieces those phases need that Ready did not — the
 * loading placeholder for the invitation hero, the calm transient progress block, the informed-consent
 * access point, and the neutral notice card — so each phase reads as the same app at a different moment
 * rather than a different screen.
 */

/**
 * The **invitation hero in its loading state**: the mark badge and the "YOU'RE INVITED" eyebrow are already
 * placed (they need no data), and the event name — the one thing still being fetched — is a quiet
 * placeholder bar. The moment details resolve into [AppEventHeaderCompact], only that bar becomes the real
 * name; the badge and eyebrow never move, so Loading → Ready does not teleport the header.
 *
 * Optimistic by design: it says "you're invited" while the fetch is in flight because that is the expected
 * outcome. A fetch that instead fails swaps the whole surface to a notice with no invitation
 * ([AppNoticeCard]), so nothing false lingers.
 */
@Composable
fun AppInvitationHeaderLoading() {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppMarkBadge(size = 54.dp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(Res.string.invited_eyebrow),
                style = eyebrowTextStyle(),
                color = appAccentText(),
            )
            // The name's placeholder: a rounded bar roughly the height of the headline it will become.
            Box(
                modifier = Modifier
                    .width(150.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(scheme.surfaceVariant),
            )
        }
    }
}

/**
 * A **calm transient progress** block for the in-flight phases (details loading, join committing): a modest
 * spinner over a muted line. Deliberately small and quiet — these phases resolve in well under a second and
 * sit between two hero surfaces, so a heavy centrepiece would flash. The surrounding phase keeps the
 * invitation hero pinned above this, so only the body changes as work runs.
 */
@Composable
fun AppJoinProgress(message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(strokeWidth = 4.dp, modifier = Modifier.size(34.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One informed-consent point in the access explainer: a small brand-tinted [icon], a short [title], and the
 * consequence [body] beneath. Stacked in the photo-access explanation ([AppInfoSheet]) with a hairline
 * between each, so the facts a guest should understand before iOS asks read as a scannable list.
 */
@Composable
fun AppAccessPoint(icon: ImageVector, title: String, body: String, divider: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    if (divider) {
        HorizontalDivider(thickness = 1.dp, color = scheme.outlineVariant)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The one-line notice above the join confirm that iOS will ask for photo access next,
 * with an info affordance ([infoDescription] names it for assistive tech) that opens the
 * explanation — an [AppInfoSheet] titled [sheetTitle] holding [explanation]. Small and centred — it is a
 * heads-up about the tap below it, not a section of the screen.
 *
 * Whether the sheet is open is this component's own state, as a dialog's visibility is: opening it changes
 * nothing a reduction needs to know, and it raises nothing.
 */
@Composable
fun AppAccessNotice(
    text: String,
    infoDescription: String,
    sheetTitle: String,
    dismissLabel: String,
    explanation: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var explaining by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        IconButton(onClick = { explaining = true }) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = infoDescription,
                tint = scheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    if (explaining) {
        AppInfoSheet(title = sheetTitle, dismissLabel = dismissLabel, onDismiss = { explaining = false }) {
            Column(content = explanation)
        }
    }
}

/**
 * The **neutral notice card** for the join gate's dead ends and retries — an invalid invite, a load
 * failure, a failed commit. Same bordered card as every other surface (16dp, `outlineVariant`, `surface`),
 * with a muted glyph badge, a bold [title] and a plain [body]. Deliberately **not** an alarm: the glyph and
 * badge are drawn in the neutral `onSurfaceVariant`/`surfaceVariant` pair rather than error red, because
 * none of these states is the guest's fault and a red slab over a friend's invite reads as broken. The
 * words carry the honesty (invalid vs. try again); the colour stays calm.
 */
@Composable
fun AppNoticeCard(icon: ImageVector, title: String, body: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(scheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onSurface,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

// The icon vocabulary the join-gate phases reach for, named at one place so the screens pass a semantic
// intent, not a glyph. Extended Material icons are available to this module (materialIconsExtended).
val JoinNoticeInvalid: ImageVector = Icons.Outlined.LinkOff
val JoinNoticeOffline: ImageVector = Icons.Outlined.CloudOff
val JoinNoticeFailed: ImageVector = Icons.Outlined.SyncProblem
val JoinAccessShare: ImageVector = Icons.Outlined.PhotoCamera
val JoinAccessLibrary: ImageVector = Icons.Outlined.PhotoLibrary
val JoinAccessChoose: ImageVector = Icons.AutoMirrored.Outlined.Rule
val JoinAccessCutoff: ImageVector = Icons.Outlined.Event
