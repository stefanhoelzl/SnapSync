package app.snapsync.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

// The caption sits on the white card, so it needs a fixed dark tone (theme-independent) to stay legible.
private val CardCaption = Color(0xFF5B6472)

/**
 * Renders [content] as a scannable QR with an optional [caption] beneath it, both on a single white
 * "pass" card (a quiet-zone margin around the code). Semantic: the call site passes only the encoded
 * string and the caption text — the QR module pattern, sizing, card, and caption treatment are owned
 * here, and the QR-rendering library never leaves this module.
 *
 * The QR always renders dark-on-light in both themes — inverted (light-on-dark) codes do not scan
 * reliably, so the skin never inverts it.
 */
@Composable
fun AppQrCode(content: String, caption: String? = null) {
    Surface(color = Color.White, shape = RoundedCornerShape(26.dp)) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = rememberQrCodePainter(content),
                contentDescription = caption,
                modifier = Modifier.size(196.dp),
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.1.sp,
                    ),
                    color = CardCaption,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
