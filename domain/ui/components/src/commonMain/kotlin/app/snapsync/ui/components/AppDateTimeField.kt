@file:OptIn(ExperimentalMaterial3Api::class)

package app.snapsync.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * A semantic date+time input — the app's first temporal picker. Appearance-free: it carries the
 * current [value] (a plain [LocalDateTime], the picked **local** wall-clock instant, or `null` when
 * unset), a change callback, and an [enabled] flag; no colors, shapes, text styles, or `Modifier`,
 * and no Material 3 type in the signature. The Material 3 `DatePicker` + `TimePicker` (and their
 * dialogs) are contained inside the component (design-system containment rule).
 *
 * Tapping the field opens a date picker and then a time picker; confirming both reports the combined
 * [LocalDateTime] via [onValueChange]. The component imposes no bounds — the default, any bounds, and
 * a "shortcut" action are the caller's concern (capability `photo-date-cutoff`). A disabled field
 * opens no picker and never invokes [onValueChange].
 */
@Composable
fun AppDateTimeField(
    value: LocalDateTime?,
    onValueChange: (LocalDateTime) -> Unit,
    enabled: Boolean = true,
) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    // The date chosen in step 1 (year, monthNumber, day), awaiting the time in step 2.
    var pendingDate by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }

    // A read-only OutlinedTextField swallows taps aimed at its own `.clickable`, and its
    // interactionSource does not fire on Compose/iOS — so the field can't open the picker itself. A
    // transparent overlay Box drawn ON TOP (last child ⇒ hit-tested first) captures the tap reliably on
    // every platform; the ripple confirms the press registered.
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value?.let(::formatLocal) ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable(enabled = enabled) { showDate = true })
    }

    if (showDate) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = value?.let(::utcMidnightMillis))
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = dateState.selectedDateMillis
                    showDate = false
                    if (millis != null) {
                        val d = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC)
                        pendingDate = Triple(d.year, d.month.ordinal + 1, d.day)
                        showTime = true
                    }
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showTime) {
        val timeState = rememberTimePickerState(
            initialHour = value?.hour ?: 0,
            initialMinute = value?.minute ?: 0,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    val date = pendingDate
                    showTime = false
                    if (date != null) {
                        val (year, month, day) = date
                        onValueChange(LocalDateTime(year, month, day, timeState.hour, timeState.minute))
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
            text = { TimePicker(state = timeState) },
        )
    }
}

/** UTC-midnight epoch millis of [value]'s date, the form the Material 3 date picker seeds from. */
private fun utcMidnightMillis(value: LocalDateTime): Long =
    LocalDateTime(value.year, value.month.ordinal + 1, value.day, 0, 0)
        .toInstant(TimeZone.UTC)
        .toEpochMilliseconds()

/** A readable `yyyy-MM-dd HH:mm` rendering of the picked local value (display only, not the cutoff). */
private fun formatLocal(value: LocalDateTime): String {
    fun p(n: Int, width: Int) = n.toString().padStart(width, '0')
    return "${p(value.year, 4)}-${p(value.month.ordinal + 1, 2)}-${p(value.day, 2)} " +
        "${p(value.hour, 2)}:${p(value.minute, 2)}"
}
