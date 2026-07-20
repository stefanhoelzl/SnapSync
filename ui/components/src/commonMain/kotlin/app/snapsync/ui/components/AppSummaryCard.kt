package app.snapsync.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The **consent layer**: one bordered card stating, in plain sentences, everything that will happen to this
 * phone when the guest taps Join.
 *
 * It is deliberately **not** a restatement of the choice above it. The choice cards are a *menu* — three
 * options a guest compares, each needing just enough words to be told apart. This card is the *receipt* for
 * the one they picked: it carries only what a comparative menu structurally cannot carry — the actual cutoff
 * instant, what the app already filters out of a camera roll, where incoming photos land, and whether an
 * album is made. Every row's subject may echo the chosen direction; every row's **predicate** is new.
 *
 * It therefore sits **below** the decision, not above it. An outcome stated before the choice that produces
 * it can only be either abstract (and so a duplicate of the menu) or true of the default alone (and so a lie
 * the moment the guest picks something else). Below, it re-renders live against the current direction and
 * cutoff, and a guest reads down the screen in causal order: who is asking → what am I choosing → what that
 * does to my phone.
 *
 * Rows are ranked by consequence, not laid out as peers: [AppSummaryFact] carries the heaviest type on the
 * screen because a cutoff instant decides which photos leave the phone; [AppSummaryLine] is a plain sentence;
 * [AppSummaryToggle] is the lightest, because an album changes nothing about what leaves.
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

/**
 * The shared style for a row's label. Medium weight, not regular: a row's label and its explanatory note
 * are both small and muted, and at identical weight the eye reads the whole row as one run of prose. The
 * half-step up is enough to make the label scan as the row's header.
 */
@Composable
private fun rowLabelStyle() =
    MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)

/** The hairline every summary row draws above itself, so the card reads as a list of separate facts. */
@Composable
private fun RowRule() {
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * The card's **consequential** row: a quiet [label], the [value] in the heaviest type on the screen, an
 * explanatory [note], an optional edit affordance, and an editor that unfolds in place ([content]).
 *
 * Demotion in this design is about rank among *questions*, never about making an answer hard to read — so
 * the cutoff instant, which decides which photos leave the phone, is the boldest text the surface renders.
 *
 * [dimmed] renders the row inert (value muted, action withdrawn) when the current direction makes it moot.
 * It stays **visible and explained** by [note] rather than vanishing, so nothing silently disappears.
 */
@Composable
fun AppSummaryFact(
    label: String,
    value: String,
    note: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    dimmed: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    RowRule()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = label,
                    style = rowLabelStyle(),
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (dimmed) scheme.onSurfaceVariant else scheme.onSurface,
                )
            }
            if (actionLabel != null && onAction != null && !dimmed) {
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = scheme.primary,
                    )
                }
            }
        }
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        content()
    }
}

/**
 * A plain outcome sentence: a quiet [label] and the [body] that states what happens. Lighter than
 * [AppSummaryFact] because it carries no value the guest chose — only a consequence of the one they did.
 */
@Composable
fun AppSummaryLine(label: String, body: String, dimmed: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    RowRule()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 11.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = label,
            style = rowLabelStyle(),
            color = scheme.onSurfaceVariant,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = if (dimmed) scheme.onSurfaceVariant else scheme.onSurface,
        )
    }
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
