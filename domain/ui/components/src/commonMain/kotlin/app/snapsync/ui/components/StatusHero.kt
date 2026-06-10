package app.snapsync.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The variant axis of the status hero. A sealed semantic value, not five components,
 * because the variant is runtime data arriving from UI state — [Progress] even carries a
 * payload. (Design-time choices remain distinct components, per the design-system rules.)
 */
sealed interface StatusIndicator {
    data object Success : StatusIndicator
    data object Warning : StatusIndicator
    data object Error : StatusIndicator
    data object Waiting : StatusIndicator

    /** Neutral photo-library glyph: an ask, not a fault. */
    data object Photos : StatusIndicator
    data class Progress(val fraction: Float) : StatusIndicator
}

/**
 * The screen's one glanceable fact: indicator inline to the left of the headline, with an
 * optional muted detail line beneath. Owns the hero's internal arrangement and typographic
 * hierarchy.
 */
@Composable
fun StatusHero(indicator: StatusIndicator, headline: String, detail: String? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IndicatorIcon(indicator)
            Text(text = headline, style = MaterialTheme.typography.titleLarge)
        }
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private val IndicatorSize = 28.dp

@Composable
private fun IndicatorIcon(indicator: StatusIndicator) {
    when (indicator) {
        is StatusIndicator.Progress -> CircularProgressIndicator(
            progress = { indicator.fraction },
            modifier = Modifier.size(IndicatorSize),
        )
        StatusIndicator.Success -> Glyph(MaterialTheme.colorScheme.primary) { successGlyph() }
        StatusIndicator.Warning -> Glyph(MaterialTheme.colorScheme.tertiary) { warningGlyph() }
        StatusIndicator.Error -> Glyph(MaterialTheme.colorScheme.error) { errorGlyph() }
        StatusIndicator.Waiting -> Glyph(MaterialTheme.colorScheme.onSurfaceVariant) { waitingGlyph() }
        StatusIndicator.Photos -> Glyph(MaterialTheme.colorScheme.onSurfaceVariant) { photosGlyph() }
    }
}

// The M3 skin draws its own glyphs: material-icons artifacts are no longer published for
// current Compose versions, and four stroke glyphs are a smaller liability than a stale
// dependency. A future skin swaps these freely.

private class GlyphScope(val draw: DrawScope, val color: Color) {
    val stroke = Stroke(
        width = with(draw) { 2.5.dp.toPx() },
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )

    fun x(fraction: Float) = draw.size.width * fraction

    fun y(fraction: Float) = draw.size.height * fraction
}

@Composable
private fun Glyph(color: Color, glyph: GlyphScope.() -> Unit) {
    Canvas(Modifier.size(IndicatorSize)) { GlyphScope(this, color).glyph() }
}

private fun GlyphScope.circle() {
    draw.drawCircle(color, radius = draw.size.minDimension / 2 - stroke.width, style = stroke)
}

private fun GlyphScope.successGlyph() {
    circle()
    val check = Path().apply {
        moveTo(x(0.32f), y(0.52f))
        lineTo(x(0.45f), y(0.65f))
        lineTo(x(0.69f), y(0.38f))
    }
    draw.drawPath(check, color, style = stroke)
}

private fun GlyphScope.errorGlyph() {
    circle()
    draw.drawLine(color, Offset(x(0.36f), y(0.36f)), Offset(x(0.64f), y(0.64f)), stroke.width, StrokeCap.Round)
    draw.drawLine(color, Offset(x(0.64f), y(0.36f)), Offset(x(0.36f), y(0.64f)), stroke.width, StrokeCap.Round)
}

private fun GlyphScope.waitingGlyph() {
    circle()
    draw.drawLine(color, Offset(x(0.5f), y(0.5f)), Offset(x(0.5f), y(0.28f)), stroke.width, StrokeCap.Round)
    draw.drawLine(color, Offset(x(0.5f), y(0.5f)), Offset(x(0.66f), y(0.58f)), stroke.width, StrokeCap.Round)
}

private fun GlyphScope.photosGlyph() {
    draw.drawRoundRect(
        color,
        topLeft = Offset(x(0.08f), y(0.16f)),
        size = Size(x(0.84f), y(0.68f)),
        cornerRadius = CornerRadius(x(0.1f)),
        style = stroke,
    )
    val mountains = Path().apply {
        moveTo(x(0.18f), y(0.72f))
        lineTo(x(0.4f), y(0.46f))
        lineTo(x(0.55f), y(0.62f))
        lineTo(x(0.67f), y(0.5f))
        lineTo(x(0.82f), y(0.72f))
    }
    draw.drawPath(mountains, color, style = stroke)
    draw.drawCircle(color, radius = stroke.width * 0.7f, center = Offset(x(0.68f), y(0.32f)))
}

private fun GlyphScope.warningGlyph() {
    val triangle = Path().apply {
        moveTo(x(0.5f), y(0.14f))
        lineTo(x(0.9f), y(0.84f))
        lineTo(x(0.1f), y(0.84f))
        close()
    }
    draw.drawPath(triangle, color, style = stroke)
    draw.drawLine(color, Offset(x(0.5f), y(0.42f)), Offset(x(0.5f), y(0.6f)), stroke.width, StrokeCap.Round)
    draw.drawCircle(color, radius = stroke.width / 2, center = Offset(x(0.5f), y(0.72f)))
}
