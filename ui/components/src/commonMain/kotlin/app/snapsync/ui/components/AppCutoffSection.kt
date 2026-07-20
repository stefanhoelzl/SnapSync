package app.snapsync.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDateTime

/**
 * Which capture-date cutoff a joining member chose (capability `photo-selection-policy`). A semantic value —
 * the caller maps it to an actual cutoff instant, so the design system stays decoupled from the config
 * capability.
 *
 * Unlike the earlier two-preset control there is now a [CUSTOM] member: a guest who arrived partway
 * through an event has no exact answer among "now" and "the start", so they get to pick the moment
 * themselves (bounded below by the event start — see [AppCutoffChoices]).
 */
enum class CutoffChoice { NOW, EVENT_START, CUSTOM }

/**
 * The capture-date cutoff as three stacked, embeddable choice rows — [CutoffChoice.NOW],
 * [CutoffChoice.EVENT_START], [CutoffChoice.CUSTOM] — each with its option name, a one-line consequence,
 * and a trailing checkmark on the chosen one. **Not a card of its own**: the rows embed inside the Share
 * section's card, because "share my photos" and "from when" are one decision surface — a separate titled
 * card implied a third question where there are only two.
 *
 * Selecting [CutoffChoice.CUSTOM] opens the app's date+time picker dialog **directly** — the row carries
 * no inline field and never restates the chosen instant, because the section's bold "Shared from …" value
 * above these rows is the one statement of the resulting cutoff (one prominent statement beats two faint
 * ones). Confirming the dialog is what commits the choice ([onCustomPicked], coerced to the floor);
 * cancelling leaves the previous selection untouched, so a curious tap costs nothing. Tapping the
 * already-selected Custom row re-opens the picker to adjust.
 *
 * The floor is enforced twice, matching the backend's silent `max(chosen, eventStart)`: the picker greys
 * out earlier days AND the confirmed value is coerced up (a day-grain calendar cannot forbid an earlier
 * *hour* on the floor's own day), so the screen can never show a date the backend would overrule. While
 * Custom is the selection its consequence line states the floor ([floorNote]) rather than repeating the
 * date the section header already carries.
 *
 * [nowAvailable] is `false` before the event has started: pre-start, "Now" would clamp to the very same
 * instant as "Event start", so its row is shown disabled (with a note) rather than as a choice that does
 * nothing.
 *
 * Appearance-free: the fixed option vocabulary, selection state, the custom value + floor cross the
 * signature — no colors, shapes, text styles, or `Modifier`. The row treatment, the checkmark, and the
 * picker dialog are owned here (design-system containment rule).
 */
@Composable
fun AppCutoffChoices(
    selected: CutoffChoice,
    onSelect: (CutoffChoice) -> Unit,
    nowAvailable: Boolean,
    customValue: LocalDateTime?,
    onCustomPicked: (LocalDateTime) -> Unit,
    minimum: LocalDateTime,
    floorNote: String,
) {
    var showPicker by remember { mutableStateOf(false) }

    // Designed to sit inside an [AppSubSection] well: rows are separated by dividers BETWEEN them only
    // (a leading divider would double the well's own top edge).
    Column(modifier = Modifier.fillMaxWidth()) {
        ChoiceRow(
            label = "Now",
            consequence = if (nowAvailable) {
                "Only photos you take from here on."
            } else {
                "Same as the event start until the event begins."
            },
            selected = selected == CutoffChoice.NOW,
            enabled = nowAvailable,
            onSelect = { onSelect(CutoffChoice.NOW) },
        )
        RowDivider()
        ChoiceRow(
            label = "Event start",
            consequence = "Everything you've taken since the event began.",
            selected = selected == CutoffChoice.EVENT_START,
            enabled = true,
            onSelect = { onSelect(CutoffChoice.EVENT_START) },
        )
        RowDivider()
        ChoiceRow(
            label = "Custom",
            // While selected, the consequence states the CONSTRAINT — never the chosen date, which the
            // section's bold "Shared from …" value already carries (the one-statement rule).
            consequence = if (selected == CutoffChoice.CUSTOM) {
                floorNote
            } else {
                "Pick your own date and time."
            },
            selected = selected == CutoffChoice.CUSTOM,
            enabled = true,
            // The tap opens the picker; only the dialog's OK commits the choice. A tap on the selected
            // row re-opens it to adjust.
            onSelect = { showPicker = true },
        )
    }

    if (showPicker) {
        DateTimePickerDialog(
            // Seed at the current custom value, else the floor — never an empty calendar.
            initial = customValue ?: minimum,
            minimum = minimum,
            onDismiss = { showPicker = false },
            onConfirm = {
                showPicker = false
                // Coerce up to the floor: the calendar greys earlier days, but the dial can still land
                // on an earlier hour of the floor's own day.
                onCustomPicked(if (it < minimum) minimum else it)
            },
        )
    }
}

/** The divider between two rows of a sub-section well — inset so it reads as a row seam, not an edge. */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/**
 * One vertical cutoff choice: the option [label], its [consequence] beneath, a trailing checkmark when
 * [selected]. The whole row is one `selectable` ([Role.RadioButton]) target — the mutually-exclusive
 * idiom, so assistive tech announces it as one of a group.
 */
@Composable
private fun ChoiceRow(
    label: String,
    consequence: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 52.dp)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
                color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
            )
            Text(
                text = consequence,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = scheme.primary,
                )
            }
        }
    }
}
