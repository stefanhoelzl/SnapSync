package app.snapsync.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The **compact** event header for a surface whose body is a decision, not a hero: the badge sits beside
 * the text instead of above it, so identity costs one line-pair of height rather than a third of the
 * screen. The join gate leads with it — the SnapSync mark as its app-icon badge, a small tracked eyebrow
 * ("YOU'RE INVITED"), the event name noticeably large beneath it, and one warm line under that. The consent
 * sections below stay factual; this is the one place that surface is allowed to sound like an invitation,
 * because it is one.
 *
 * It exists because the join surface's job is to ask a question. A full-height hero there pushed the
 * question below the fold and left the answer controls competing for what was left.
 */
@Composable
fun AppEventHeaderCompact(title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppMarkBadge(size = 54.dp)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = "YOU'RE INVITED",
                style = eyebrowTextStyle(),
                color = appAccentText(),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The **compact host header** for the create-event surface: the SnapSync mark as its app-icon badge,
 * a small tracked eyebrow naming the verb ("HOST AN EVENT"), the title noticeably large beneath it, and
 * one warm line under that. Same shape and rank as [AppEventHeaderCompact] — the *invitation* header the
 * join gate leads with — so the two surfaces read as one product hosting two verbs: a guest is invited;
 * a host founds. Only the eyebrow differs.
 *
 * It is deliberately the compact (badge-beside-text, left-aligned) form rather than a full-height centered
 * hero: the create surface's body is a short form, and keeping identity to one line-pair leaves the form —
 * and the transient "creating …" state that replaces it — anchored in the same place, so nothing jumps.
 *
 * Appearance-free: the call site passes only the two strings; the badge, eyebrow, tint, and typographic
 * hierarchy are owned here.
 */
@Composable
fun AppEventHeaderHost(title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppMarkBadge(size = 54.dp)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = "HOST AN EVENT",
                style = eyebrowTextStyle(),
                color = appAccentText(),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The **centered** event hero: the SnapSync mark as its app-icon badge stacked above the [title] and a
 * muted [subtitle]. The camera glyph this once carried is gone — the surface now leads with the real
 * app mark (the same geometry as the icon, see [AppMarkBadge]), so the front door wears the product's
 * own face rather than a generic camera. Semantic — the call site passes only the two strings.
 *
 * Retained as the centered variant of [AppEventHeaderHost]; the create surface itself uses the compact
 * header so its short form stays anchored.
 */
@Composable
fun AppEventHero(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppMarkBadge(size = 76.dp)
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
