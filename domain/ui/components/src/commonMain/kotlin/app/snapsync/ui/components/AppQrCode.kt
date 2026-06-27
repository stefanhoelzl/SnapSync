package app.snapsync.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

/**
 * Renders [content] as a scannable QR with an optional [caption] beneath it. Semantic: the call site
 * passes only the encoded string and the caption text — the QR module pattern, quiet zone, sizing,
 * and the caption's muted treatment are owned here, and the QR-rendering library never leaves this
 * module. Owns its internal arrangement (QR above caption), per design.md §5.
 */
@Composable
fun AppQrCode(content: String, caption: String? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = rememberQrCodePainter(content),
            contentDescription = caption,
            modifier = Modifier.size(220.dp),
        )
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
