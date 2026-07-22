package app.snapsync.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDateTime

/**
 * The event's **date range** on the create screen (capability `event-creation-ui`): the chosen
 * `[from, until]` window as a readable label plus a live humanized duration hint, with an edit affordance
 * beside it that opens the design system's **dual-handle** date+time picker, and one consequence note
 * beneath. It supersedes the earlier single start-date section — an event now declares a whole window, not
 * just a start.
 *
 * Appearance-free: it carries only the current [from]/[until] instants, two formatting lambdas
 * ([rangeLabel] for the readable span, [durationLabel] for the "Event lasts …" hint — the caller owns the
 * app's date/duration formatting, `:ui:components` never re-derives it), the consequence [note], and a
 * change callback. No colors, text styles, shapes, elevations, or `Modifier`, and no Material 3 type in the
 * signature.
 *
 * The range is **required** — an event always has a start and an end, so there is no unset state. The caller
 * owns the default (`[now, now + 1 day]`, frozen at first composition — see `event-creation-ui`); the
 * picker imposes **no** window (only `start < end`, which the create screen guards), so a host can set a
 * range arbitrarily far in the past or the future.
 *
 * Label + duration + affordance + note as one component keeps the arrangement — "the range is a
 * consequence, here is what it means" — a **convention** the design system owns, not a layout each screen
 * re-derives.
 */
@Composable
fun AppEventDateRangeSection(
    from: LocalDateTime,
    until: LocalDateTime,
    rangeLabel: (LocalDateTime, LocalDateTime) -> String,
    durationLabel: (LocalDateTime, LocalDateTime) -> String,
    note: String,
    onRangeChange: (from: LocalDateTime, until: LocalDateTime) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var showPicker by remember { mutableStateOf(false) }

    Surface(
        color = scheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 6.dp, top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = rangeLabel(from, until),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                    )
                    Text(
                        text = durationLabel(from, until),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { showPicker = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit event dates")
                }
            }
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            )
        }
    }

    if (showPicker) {
        DateTimeRangePickerDialog(
            initialFrom = from,
            initialUntil = until,
            // The create surface imposes no window — a host may reach arbitrarily far in either direction.
            minimum = null,
            maximum = null,
            onDismiss = { showPicker = false },
            onConfirm = { f, u ->
                showPicker = false
                onRangeChange(f, u)
            },
        )
    }
}

/**
 * The design system's human rendering of a wall-clock instant — `14 Jul 2026, 18:00`.
 *
 * Public because a screen sometimes needs to state a date in prose
 * (the Share section's "Shared from …" value), and a screen must never re-derive the app's date format.
 */
fun appDateTimeLabel(value: LocalDateTime): String = formatStart(value)

/** `14 Jul 2026, 18:00` — a human rendering of the start, in the device's local wall-clock terms. */
internal fun formatStart(value: LocalDateTime): String {
    fun p(n: Int) = n.toString().padStart(2, '0')
    return "${value.day} ${monthAbbrev(value.month.ordinal)} ${value.year}, " +
        "${p(value.hour)}:${p(value.minute)}"
}

/** `14 Jul, 18:00` — the shorter form the status line uses, where the year is noise. */
internal fun formatStartShort(value: LocalDateTime): String {
    fun p(n: Int) = n.toString().padStart(2, '0')
    return "${value.day} ${monthAbbrev(value.month.ordinal)}, ${p(value.hour)}:${p(value.minute)}"
}

private fun monthAbbrev(monthOrdinal: Int): String = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)[monthOrdinal]
