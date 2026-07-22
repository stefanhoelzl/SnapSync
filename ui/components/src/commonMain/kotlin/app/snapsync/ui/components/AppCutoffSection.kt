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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDateTime

/**
 * The lower bound of a joining member's capture-date **range** (capability `photo-selection-policy`). A
 * semantic value — the caller maps it to an actual instant against the event window, so the design system
 * stays decoupled from the config capability.
 *
 * [EVENT_START] shares everything back to the event's start; [NOW] starts the contribution at the present
 * instant (offered only while the present is inside the event window — see [AppRangePresetChoices]); [CUSTOM]
 * lets a guest who arrived partway through pick their own start, bounded to the window.
 */
enum class FromChoice { EVENT_START, NOW, CUSTOM }

/**
 * The upper bound of a joining member's capture-date **range** (capability `photo-selection-policy`).
 * [EVENT_END] shares up to when the event ends (the default — narrow, never widen); [CUSTOM] lets a guest
 * stop contributing earlier, bounded to the window.
 */
enum class UntilChoice { EVENT_END, CUSTOM }

/**
 * The capture-date **range** as two handles of stacked, embeddable choice rows — a **From** group
 * ([FromChoice.EVENT_START] / [FromChoice.NOW] / [FromChoice.CUSTOM]) and an **Until** group
 * ([UntilChoice.EVENT_END] / [UntilChoice.CUSTOM]) — each row with its option name, a one-line consequence,
 * and a trailing checkmark on the chosen one. **Not a card of its own**: the rows embed inside the Share
 * section's card, because "share my photos" and "from when / until when" are one decision surface.
 *
 * Selecting a **Custom** row opens the app's date+time picker dialog **directly**, constrained to the event
 * window `[windowStart, windowEnd]` — the row carries no inline field and never restates the chosen instant,
 * because the embedding section's own value line is the one statement of the resulting range. Confirming the
 * dialog commits the choice (coerced into the window via that handle's Custom-pick callback); cancelling
 * leaves the previous selection untouched. Tapping the already-selected Custom row re-opens the picker.
 *
 * [nowAvailable] is `false` when the present is outside the event window (`now < startsAt` or `now > endsAt`):
 * the **Now** row is then shown **disabled** (with a note) rather than hidden, so the control's shape does not
 * change between events.
 *
 * Appearance-free: the two option vocabularies, the two selections, the custom values, the window bounds, and
 * the two constraint notes cross the signature — no colors, shapes, text styles, or `Modifier`, no Material 3
 * type. The row treatment, the checkmark, and the picker dialog are owned here (design-system containment).
 */
@Composable
fun AppRangePresetChoices(
    fromSelected: FromChoice,
    onFromSelect: (FromChoice) -> Unit,
    fromCustomValue: LocalDateTime?,
    onFromCustomPicked: (LocalDateTime) -> Unit,
    untilSelected: UntilChoice,
    onUntilSelect: (UntilChoice) -> Unit,
    untilCustomValue: LocalDateTime?,
    onUntilCustomPicked: (LocalDateTime) -> Unit,
    nowAvailable: Boolean,
    windowStart: LocalDateTime,
    windowEnd: LocalDateTime,
    fromFloorNote: String,
    untilCeilingNote: String,
) {
    var showFromPicker by remember { mutableStateOf(false) }
    var showUntilPicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        GroupCaption("Share from")
        ChoiceRow(
            tag = "from-event-start",
            label = "Event start",
            consequence = "Everything you've taken since the event began.",
            selected = fromSelected == FromChoice.EVENT_START,
            enabled = true,
            onSelect = { onFromSelect(FromChoice.EVENT_START) },
        )
        RowDivider()
        ChoiceRow(
            tag = "from-now",
            label = "Now",
            consequence = if (nowAvailable) {
                "Only photos you take from here on."
            } else {
                "Same as the event start until the event begins."
            },
            selected = fromSelected == FromChoice.NOW,
            enabled = nowAvailable,
            onSelect = { onFromSelect(FromChoice.NOW) },
        )
        RowDivider()
        ChoiceRow(
            tag = "from-custom",
            label = "Custom",
            // While selected the row states the CONSTRAINT (the floor), never the chosen date, which the
            // section's value line already carries.
            consequence = if (fromSelected == FromChoice.CUSTOM) fromFloorNote else "Pick your own start.",
            selected = fromSelected == FromChoice.CUSTOM,
            enabled = true,
            onSelect = { showFromPicker = true },
        )

        GroupCaption("Share until")
        ChoiceRow(
            tag = "until-event-end",
            label = "Event end",
            consequence = "Everything up to when the event ends.",
            selected = untilSelected == UntilChoice.EVENT_END,
            enabled = true,
            onSelect = { onUntilSelect(UntilChoice.EVENT_END) },
        )
        RowDivider()
        ChoiceRow(
            tag = "until-custom",
            label = "Custom",
            consequence = if (untilSelected == UntilChoice.CUSTOM) untilCeilingNote else "Pick your own end.",
            selected = untilSelected == UntilChoice.CUSTOM,
            enabled = true,
            onSelect = { showUntilPicker = true },
        )
    }

    if (showFromPicker) {
        DateTimePickerDialog(
            // Seed at the current custom value, else the window start — never an empty calendar.
            initial = fromCustomValue ?: windowStart,
            minimum = windowStart,
            maximum = windowEnd,
            onDismiss = { showFromPicker = false },
            onConfirm = {
                showFromPicker = false
                // Coerce into the window: the calendar greys days outside it, but the wheels can still land
                // on a boundary-day hour outside it.
                onFromCustomPicked(it.coerceIn(windowStart, windowEnd))
            },
        )
    }
    if (showUntilPicker) {
        DateTimePickerDialog(
            initial = untilCustomValue ?: windowEnd,
            minimum = windowStart,
            maximum = windowEnd,
            onDismiss = { showUntilPicker = false },
            onConfirm = {
                showUntilPicker = false
                onUntilCustomPicked(it.coerceIn(windowStart, windowEnd))
            },
        )
    }
}

/** The small caption heading a handle's group of rows. */
@Composable
private fun GroupCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
    )
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
 * One vertical choice: the option [label], its [consequence] beneath, a trailing checkmark when [selected].
 * The whole row is one `selectable` ([Role.RadioButton]) target — the mutually-exclusive idiom. It also
 * carries a [tag] so offscreen tests can address a specific row (the two "Custom" rows share a label, so
 * text alone is ambiguous by construction).
 */
@Composable
private fun ChoiceRow(
    tag: String,
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
            .testTag(tag)
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
