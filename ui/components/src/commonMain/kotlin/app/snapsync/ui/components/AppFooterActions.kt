package app.snapsync.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * The footer actions (capability `sync-status`): borderless text actions, each led by its glyph, in rows of equal
 * halves — on the joined screen the invite (share, QR code) above the membership (settings, Leave); on the join gate
 * Join above Cancel, a row each, so the longer "Join & allow photos" keeps its whole label. Emphasis and glyph are
 * design-time choices, so each meaning is its own component and the call site passes only a label and a click.
 */

/** A row of footer actions: each takes an equal part, so a lone action sits centred. */
@Composable
fun AppFooterTextActions(content: @Composable RowScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** Shares the event's invite link: the share glyph, in the accent colour. */
@Composable
fun RowScope.ShareTextAction(label: String, onClick: () -> Unit) {
    FooterTextAction(label, Icons.Filled.Share, appAccentText(), onClick)
}

/** Shows the event's QR code: the QR glyph, in the accent colour. */
@Composable
fun RowScope.QrTextAction(label: String, onClick: () -> Unit) {
    FooterTextAction(label, Icons.Filled.QrCode, appAccentText(), onClick)
}

/** Opens the event's settings: the settings glyph, in the accent colour. */
@Composable
fun RowScope.SettingsTextAction(label: String, onClick: () -> Unit) {
    FooterTextAction(label, Icons.Filled.Settings, appAccentText(), onClick)
}

/** Leaves the event: the exit glyph, in the error colour, because leaving is destructive. */
@Composable
fun RowScope.LeaveTextAction(label: String, onClick: () -> Unit) {
    FooterTextAction(label, Icons.AutoMirrored.Filled.Logout, MaterialTheme.colorScheme.error, onClick)
}

/** Joins the event: the check glyph, in the accent colour; [enabled] false while the join cannot go ahead. */
@Composable
fun RowScope.JoinTextAction(label: String, onClick: () -> Unit, enabled: Boolean) {
    FooterTextAction(label, Icons.Filled.Check, appAccentText(), onClick, enabled)
}

/** Leaves a surface without committing it: the close glyph, in the quiet text colour. */
@Composable
fun RowScope.CancelTextAction(label: String, onClick: () -> Unit) {
    FooterTextAction(label, Icons.Filled.Close, MaterialTheme.colorScheme.onSurfaceVariant, onClick)
}

@Composable
private fun RowScope.FooterTextAction(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
    ) {
        // Decorative: the label beside it already says what the action does.
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
