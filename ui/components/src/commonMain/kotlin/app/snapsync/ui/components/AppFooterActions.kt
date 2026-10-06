package app.snapsync.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * The joined screen's quiet footer actions (capability `sync-status`): centred, borderless text buttons that
 * sit beneath the invite's filled pair without competing with it. Emphasis is a design-time choice, so each
 * meaning is its own component and the call site passes only a label and a click.
 */

/** Opens the event's settings: a text action in the accent colour. */
@Composable
fun SettingsTextAction(label: String, onClick: () -> Unit) = FooterTextAction(label, appAccentText(), onClick)

/** Leaves the event: a text action in the error colour, because leaving is destructive. */
@Composable
fun LeaveTextAction(label: String, onClick: () -> Unit) =
    FooterTextAction(label, MaterialTheme.colorScheme.error, onClick)

/** The thin line that sets the footer's quiet actions apart from the invite pair above them. */
@Composable
fun AppFooterDivider() {
    HorizontalDivider(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun FooterTextAction(label: String, color: Color, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}
