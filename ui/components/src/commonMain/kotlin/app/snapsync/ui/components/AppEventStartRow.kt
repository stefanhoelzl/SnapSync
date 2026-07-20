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
 * The event's **start date** on the create screen (capability `event-creation-ui`): the chosen start as a
 * readable label, with an edit affordance beside it that opens the design system's date+time picker.
 *
 * Appearance-free: it carries only the current [value] and a change callback — no colors, text styles,
 * shapes, elevations, or `Modifier`, and no Material 3 type in the signature.
 *
 * [value] is **non-null**: an event always has a start, so there is no unset state to represent. The
 * caller owns the default (now, frozen at first composition — see `event-creation-ui`) and any bounds
 * (there are none: an event may start arbitrarily far in the past *or* the future).
 *
 * Label-plus-affordance is one component rather than two composed at the screen so that the arrangement —
 * value on the left, edit on the right, one tap to change it — is a **convention** the design system owns,
 * not a layout each screen re-derives.
 */
@Composable
private fun AppEventStartRow(value: LocalDateTime, onValueChange: (LocalDateTime) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Starts ${formatStart(value)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = { showPicker = true }) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit start date")
        }
    }

    if (showPicker) {
        DateTimePickerDialog(
            initial = value,
            onDismiss = { showPicker = false },
            onConfirm = {
                showPicker = false
                onValueChange(it)
            },
        )
    }
}

/**
 * The event's start as a **stated-consequence card** (capability `event-creation-ui`): the same bordered
 * card frame the join gate's sections carry, holding the [AppEventStartRow] and one [note] line beneath it.
 *
 * The start is not a bare form field here — it is the floor of every guest's capture-date cutoff, so it
 * earns the same card idiom (16dp radius, `outlineVariant` border, `surface` fill) and a plain sentence
 * saying what it does. The row inside keeps its `Starts …` label and `Edit start date` affordance; the
 * card and the [note] are what this adds.
 *
 * Appearance-free: the current [value], a change callback, and the consequence [note] string — no colors,
 * shapes, or `Modifier` cross the signature.
 */
@Composable
fun AppEventStartSection(value: LocalDateTime, onValueChange: (LocalDateTime) -> Unit, note: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp)) {
                AppEventStartRow(value = value, onValueChange = onValueChange)
            }
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 0.dp, bottom = 12.dp),
            )
        }
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
