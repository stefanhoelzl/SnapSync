package app.snapsync.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The lower-emphasis companion to [PrimaryButton] — same geometry (full-width, 52dp tall, 16dp
 * corners), only a lighter (outlined) fill, so the two share one button shape and differ only in
 * emphasis. Used for the secondary/escape action beside a primary one (e.g. "Stay" beside "Leave").
 */
@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
