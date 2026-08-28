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
fun AppEventHeaderCompact(title: String, subtitle: String) =
    AppIdentityHeader("YOU'RE INVITED", title, subtitle)

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
fun AppEventHeaderHost(title: String, subtitle: String) =
    AppIdentityHeader("HOST AN EVENT", title, subtitle)

/**
 * The shape both headers above are, and the one any other surface needing identity-plus-a-statement
 * uses: the SnapSync mark as an app-icon badge, a small tracked [eyebrow] naming what this surface IS,
 * the [title] noticeably large beneath it, and one line of [subtitle] under that.
 *
 * Extracted because the two headers were byte-identical apart from that one string — their own docs said
 * "only the eyebrow differs" — and because reaching for the nearest existing header put **"HOST AN
 * EVENT"** above the update-required screen on a real device. A surface that is not about hosting must
 * be able to say what it IS without borrowing another surface's verb, and a shared header with the
 * eyebrow as a parameter is what makes that possible without a third copy of this Row.
 */
@Composable
fun AppIdentityHeader(eyebrow: String, title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppMarkBadge(size = 54.dp)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = eyebrow,
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
