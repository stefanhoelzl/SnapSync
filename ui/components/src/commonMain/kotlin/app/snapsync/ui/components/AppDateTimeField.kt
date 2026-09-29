package app.snapsync.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

import kotlinx.datetime.todayIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.RowScope

/**
 * A calendar week is seven columns wide. Named because `row * 7 + col` and `(cells + 6) / 7` read as
 * arithmetic noise otherwise: the first is "which cell of the grid", the second is the ceiling division
 * that asks how many rows a month needs — and `6` there is only ever `DAYS_PER_WEEK - 1`, the round-up
 * addend, not a day of the week.
 */
internal const val DAYS_PER_WEEK = 7
internal const val ROUND_UP_TO_WHOLE_WEEK = DAYS_PER_WEEK - 1

/**
 * How visible a wheel item is by its distance from the selection: the centre is solid, its immediate
 * neighbours are half-lit, and everything beyond fades to a quarter. The gradient is what makes the
 * column read as a wheel rather than a list.
 */
internal const val WHEEL_SELECTED_ALPHA = 1f
internal const val WHEEL_NEIGHBOUR_ALPHA = 0.5f
internal const val WHEEL_DISTANT_ALPHA = 0.25f

/**
 * The range picker as a dialog (capabilities `join-event`, `manage-membership`): the same [RangeEditor] the
 * create screen shows inline — calendar, gestures, wheels, rules — inside a pane-centred card with a [title],
 * optional [presets] as chips on top, and Cancel / OK. The join and settings surfaces open it on the range
 * already chosen ([initial], complete), so the first tap on a day starts a new range and narrowing an end is
 * a drag of that end. [bounds] hold it to the event's window.
 *
 * Nothing leaves the dialog until OK, which is enabled only while the range is valid; a chip is a complete
 * choice the caller commits on tap, so it bypasses OK; Cancel changes nothing.
 */
@Composable
internal fun RangePickerDialog(
    initial: EventRange,
    bounds: RangeBounds,
    title: String,
    presets: List<RangePresetChip>,
    onDismiss: () -> Unit,
    onConfirm: (from: LocalDateTime, until: LocalDateTime) -> Unit,
) {
    var range by remember { mutableStateOf(initial) }
    val end = range.until?.takeIf { range.isValid(bounds) }
    PickerDialogShell(
        title = title,
        onDismiss = onDismiss,
        onConfirm = { end?.let { onConfirm(range.from, it) } },
        confirmEnabled = end != null,
    ) {
        if (presets.isNotEmpty()) PresetChips(presets)
        RangeEditor(range, bounds) { range = it }
    }
}

/**
 * A one-tap shortcut above the range calendar (capability `join-event`: the whole event, from now): a
 * [label], whether it is the range currently chosen, and what choosing it does. Tapping one is a complete
 * choice — the caller commits it and closes the dialog — so a chip never half-edits the calendar beneath.
 */
class RangePresetChip(val label: String, val selected: Boolean, val onClick: () -> Unit)

/**
 * The preset chips as one wrapping row: a selected chip is filled in the brand's container tone, the rest are
 * outlined. Each is one `selectable` target ([Role.RadioButton]) — the presets are mutually exclusive, and
 * with a custom range chosen none is selected.
 */
@Composable
private fun PresetChips(presets: List<RangePresetChip>) {
    val scheme = MaterialTheme.colorScheme
    val shape = CircleShape // 50% corners: a pill on a wider-than-tall chip
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { chip ->
            Box(
                modifier = Modifier
                    .border(1.dp, if (chip.selected) scheme.primary else scheme.outlineVariant, shape)
                    .background(if (chip.selected) scheme.primaryContainer else scheme.surface, shape)
                    .selectable(selected = chip.selected, role = Role.RadioButton, onClick = chip.onClick)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = chip.label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (chip.selected) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = if (chip.selected) scheme.onPrimaryContainer else scheme.onSurface,
                )
            }
        }
    }
}

/**
 * The dialog's frame: the pane-centred popup card, its heading, the caller's content, then Cancel / OK.
 *
 * **Why a `Popup`, not an `AlertDialog`.** An M3 dialog is a *window-centered* overlay: on a real phone the
 * window IS the 390pt screen, so it centers fine, but the multi-pane desktop harness embeds the phone pane
 * in a much wider host window, and a window-centered dialog then overflows the pane's right edge (which is
 * exactly why the old M3 `DatePicker` clipped there). This renders in-tree as a `Popup` positioned centred
 * **within the pane** — the enclosing full-width anchor gives [PaneCenteredProvider] the pane's own bounds
 * — so the full picker is visible at 390pt on device and in the harness alike.
 */
@Composable
private fun PickerDialogShell(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // The full-width anchor: its bounds ARE the pane content width, so the position provider can centre the
    // card within the pane rather than within the (wider, in the harness) host window.
    Box(modifier = Modifier.fillMaxWidth()) {
        Popup(
            popupPositionProvider = remember { PaneCenteredProvider() },
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true),
        ) {
            Surface(
                modifier = Modifier.width(340.dp),
                shape = RoundedCornerShape(24.dp),
                color = scheme.surface,
                border = BorderStroke(1.dp, scheme.outlineVariant),
                shadowElevation = 16.dp,
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = scheme.onSurface,
                        // Announce the dialog's title as a heading so VoiceOver states what opened.
                        modifier = Modifier.semantics { heading() },
                    )
                    content()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.weight(1f)) { SecondaryButton(label = "Cancel", onClick = onDismiss) }
                        Box(Modifier.weight(1f)) {
                            PrimaryButton(label = "OK", onClick = onConfirm, enabled = confirmEnabled)
                        }
                    }
                }
            }
        }
    }
}

/** Full weekday names for a day cell's spoken date. `dayOfWeek.ordinal` is Monday = 0. */
internal fun weekdayName(date: LocalDate): String = listOf(
    "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
)[date.dayOfWeek.ordinal]

/** Full month names for the calendar header. */
internal fun monthName(monthNumber: Int): String = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)[monthNumber - 1]
