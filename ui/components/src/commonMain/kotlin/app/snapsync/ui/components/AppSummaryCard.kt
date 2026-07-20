package app.snapsync.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A **titled statement card**: an eyebrow-cased title over a content slot of rows. The join gate's
 * photo-access explainer is its live use — the "WHAT JOINING DOES" card of [AppAccessPoint] consent
 * rows — and [AppSummaryToggle] rides in it wherever a single checkmark row needs the card frame.
 *
 * It once carried the Ready surface's full "receipt" (fact/line rows stating the membership's
 * consequences); that surface now states consequences inside its switch sections, and the unused row
 * kinds were swept. What remains is the frame and the two rows that are still alive.
 *
 * Appearance-free: a title string and a content slot — no colors, text styles, shapes, or `Modifier`.
 */
@Composable
fun AppSummaryCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        // `surface`, the same plane as the choice cards — deliberately, after trying the alternative.
        // Sinking this to `background` did read as a different species, but it also made the consent layer
        // the faintest block on the screen, which is the wrong rank for the one region that states what
        // joining does to your phone. The species distinction is carried instead by structure that costs no
        // contrast: the header rule, the internal hairlines, and the fact that nothing in here is tappable
        // as a whole.
        color = scheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title.uppercase(),
                style = eyebrowTextStyle(),
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 9.dp),
            )
            content()
        }
    }
}

/** The hairline every summary row draws above itself, so the card reads as a list of separate facts. */
@Composable
private fun RowRule() {
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}


/**
 * The card's opt-in row — the lightest thing on the surface, because it changes nothing about what leaves
 * the phone. Rendered as a row of the summary rather than a bare checkbox floating beside it: an album is
 * one of the things that happens when you join, so it is stated where all the others are.
 *
 * [dimmed] disables it for a direction that receives nothing, with [note] saying why.
 *
 * The control is a **trailing checkmark**, the iOS list-selection idiom — no checkbox (iOS has none, and
 * one reads as a web form) and no switch (M3's draws an outlined thumb when off, which reads Android).
 *
 * The *off* state draws a **faint outlined circle** where the check would sit, so a control is visibly
 * present even unchecked (the bare glyph would otherwise draw nothing when off); the checked state keeps the
 * green check. [note] still carries the state in words too, so the row reads correctly without the glyph.
 *
 * The whole row is the target and the checkmark is decoration: two live targets in one row double-fire the
 * toggle, which cancels itself out and reads as a dead control. One `toggleable` carrying `Role.Checkbox`
 * gives assistive tech a single node announcing checked/unchecked — accurate here, where a switch's
 * "applies immediately" contract would not be (this commits with Join). When [dimmed] the same node stays
 * in the tree as a **disabled** checkbox (`enabled = false`) rather than dropping semantics, so assistive
 * tech still finds a control and reports it unavailable instead of it silently vanishing.
 */
@Composable
fun AppSummaryToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    note: String? = null,
    dimmed: Boolean = false,
    // `false` when the row sits inside an [AppSubSection] well, whose own top edge already separates it.
    divider: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    if (divider) {
        RowRule()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                // Dimmed keeps the node in the semantics tree as a disabled checkbox rather than dropping
                // it — a control that reports "unavailable" beats one that silently disappears.
                enabled = !dimmed,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .heightIn(min = 48.dp)
            .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (dimmed) scheme.onSurfaceVariant else scheme.onSurface,
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (checked && !dimmed) {
                Icon(
                    imageVector = Icons.Default.Check,
                    // The row's own semantics already announce the state; a description here would make
                    // assistive tech say it twice.
                    contentDescription = null,
                    tint = scheme.primary,
                )
            } else {
                // The off (or dimmed) affordance: a faint outlined circle where the check would sit, so a
                // control is visibly present even when unchecked. Decoration only — the row's `toggleable`
                // owns the semantics.
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .border(1.5.dp, scheme.outline, CircleShape),
                )
            }
        }
    }
}

/**
 * The screen's **one question** — the single thing the surface is asking. Left-aligned and set above the
 * controls that answer it, so the binding between question and answer is positional, not inferred.
 */
@Composable
fun AppQuestionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
}
