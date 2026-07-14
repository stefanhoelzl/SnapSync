package app.snapsync.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A full-screen explanation that precedes a consequential system prompt (capability `design-system`,
 * "App explainer component"): the neutral photo-library glyph, a [headline], and a body of short
 * [paragraphs].
 *
 * Content only — no appearance parameters, and no `Modifier`, color, shape, text style, or Material 3
 * type in the signature. The indicator is deliberately [StatusIndicator.Photos], the **neutral** glyph:
 * this is an ask, not a fault, so it must never carry a warning or error treatment.
 *
 * The component owns the paragraph arrangement so no caller composes raw geometry, and it owns **no
 * actions**: the calling screen pins its confirm/cancel into the same bottom action cluster the screen's
 * other surfaces use, so the buttons line up across phases.
 */
@Composable
fun AppExplainer(headline: String, paragraphs: List<String>) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IndicatorIcon(StatusIndicator.Photos)
        Text(text = headline, style = MaterialTheme.typography.titleLarge)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            paragraphs.forEach { paragraph ->
                Text(
                    text = paragraph,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
