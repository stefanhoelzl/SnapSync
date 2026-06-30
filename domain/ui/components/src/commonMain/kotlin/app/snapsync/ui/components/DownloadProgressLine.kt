package app.snapsync.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign

/**
 * The joined-layer download-progress line (capability `photo-download`): a muted, glanceable
 * "Downloaded [downloaded] of [total]" beneath the upload hero — the count of other contributors'
 * photos imported into the library. Semantic (carries only the two counts; no appearance params);
 * the screen shows it only when there is something foreign to collect.
 */
@Composable
fun DownloadProgressLine(downloaded: Int, total: Int) {
    Text(
        text = "Downloaded $downloaded of $total",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
