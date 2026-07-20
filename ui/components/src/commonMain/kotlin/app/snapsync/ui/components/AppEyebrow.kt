package app.snapsync.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The **one letter-spacing** the eyebrow idiom uses everywhere. The idiom had drifted to three tracks
 * (1.1 / 1.4 / 1.5sp) across the summary-card section labels, the header eyebrows, and [AppEyebrow]; they
 * are now a single value routed through [eyebrowTextStyle] so the treatment reads as one voice.
 */
val EyebrowTracking: TextUnit = 1.4.sp

/**
 * The shared eyebrow type: the design language's small uppercase, bold, wide-tracked label style. Every
 * eyebrow surface — [AppEyebrow], the invitation/host headers, the summary-card section labels — copies
 * from this one style so the tracking (and any future tuning) lives in exactly one place. Colour and
 * alignment stay with the call site, which is the only thing that legitimately differs between them.
 */
@Composable
fun eyebrowTextStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = EyebrowTracking,
    )

/**
 * A **tracked eyebrow** — the small uppercase, wide-tracked label the design language uses to name what a
 * region is *for* before the region itself (the invitation hero's "YOU'RE INVITED", the summary card's
 * section labels). Extracted as a standalone `App*` so the joined layer can name its QR ("SHARE THIS
 * EVENT") in the same voice, rather than each surface re-deriving the treatment inline.
 *
 * The [tone] carries the one design-time choice: [EyebrowTone.Accent] for a purpose the user acts on
 * (the text-safe brand accent, [appAccentText]), [EyebrowTone.Muted] for a quiet section label
 * (onSurfaceVariant). Appearance-free otherwise — the caller passes only text and rank.
 */
enum class EyebrowTone { Accent, Muted }

@Composable
fun AppEyebrow(text: String, tone: EyebrowTone = EyebrowTone.Muted) {
    Text(
        text = text.uppercase(),
        style = eyebrowTextStyle(),
        color = when (tone) {
            EyebrowTone.Accent -> appAccentText()
            EyebrowTone.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center,
    )
}
