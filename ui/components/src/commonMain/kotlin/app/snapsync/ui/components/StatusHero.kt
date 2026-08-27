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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector


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

// The LED palette: this skin's mapping of the semantic sync states to color. A future
// (e.g. Cupertino) skin may paint them differently; the screens never see these. The complete
// state uses the brand primary (accents unified on primary — no standalone green).
private val LedYellow = Color(0xFFE0A100)

@Composable
internal fun IndicatorIcon(indicator: StatusIndicator) {
    when (indicator) {
        StatusIndicator.Loading -> CircularProgressIndicator(modifier = Modifier.size(IndicatorSize))
        StatusIndicator.InProgress -> LedDot(LedYellow)
        StatusIndicator.Complete -> LedDot(MaterialTheme.colorScheme.primary)
        StatusIndicator.Success -> Glyph(Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.primary)
        StatusIndicator.Error -> Glyph(Icons.Outlined.Cancel, MaterialTheme.colorScheme.error)
        StatusIndicator.Waiting -> Glyph(Icons.Outlined.Schedule, MaterialTheme.colorScheme.onSurfaceVariant)
        StatusIndicator.Photos -> Glyph(Icons.Outlined.Image, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A small filled disc — the status LED. */
@Composable
private fun LedDot(color: Color) {
    Canvas(Modifier.size(IndicatorSize)) {
        drawCircle(color, radius = size.minDimension * 0.3f)
    }
}

// The glyphs come from the Material icon artifact (`compose.materialIconsExtended`, declared by this
// module) rather than being hand-drawn on a Canvas.
//
// This file previously drew them itself, on the stated grounds that "material-icons artifacts are no
// longer published for current Compose versions". That was not true when it was checked: the artifact
// is a declared dependency of this module and twenty of its icons are already used across the design
// system, including three of the four below. A forcing proof that cites a dependency's availability is
// only worth keeping while it is true, so it is retired here rather than left to mislead the next
// reader (capability `design-system` already places this artifact in this module and states that a
// component's glyph is the skin's choice).
//
// Three of the four indicators drew a circle around their own stroke, so the circle-inclusive Material
// equivalents are the closer match, not a compromise: a clock face IS a circle with hands.

@Composable
private fun Glyph(icon: ImageVector, color: Color) {
    // No content description: the hero's headline and detail carry the meaning in text, and an icon
    // that restated them would be announced twice.
    Icon(icon, contentDescription = null, modifier = Modifier.size(IndicatorSize), tint = color)
}
