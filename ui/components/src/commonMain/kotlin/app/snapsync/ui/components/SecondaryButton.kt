package app.snapsync.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The lower-emphasis companion to [PrimaryButton] — same footprint (full-width, 52dp tall, so the touch
 * target is unchanged), but **borderless text**: no fill and no outline, only a muted-but-AA label. It is
 * the secondary/escape action beside a primary one (e.g. "Cancel" beside "Join"), and the emphasis gap is
 * deliberate: an outlined pill of equal weight beside the filled primary reads as an Android button pair,
 * where iOS pairs a filled primary with a plain text action. The primary stays the only filled pill.
 */
@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        // `onSurfaceVariant` clears AA on both themes' surfaces while sitting clearly below the filled
        // primary — the muted-but-legible weight a text action wants.
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
