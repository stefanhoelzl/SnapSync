@file:OptIn(ExperimentalMaterial3Api::class)

package app.snapsync.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * A semantic date+time input — the app's temporal picker. Appearance-free: it carries the current
 * [value] (a plain [LocalDateTime], the picked **local** wall-clock instant, or `null` when unset), a
 * change callback, and an [enabled] flag; no colors, shapes, text styles, or `Modifier`, and no Material
 * 3 type in the signature. The Material 3 `DatePicker` + `TimePicker` (and their dialogs) are contained
 * inside the component (design-system containment rule).
 *
 * Tapping the field opens **one** dialog carrying both the calendar and an `[HH]:[MM]` readout; tapping
 * the readout swaps the calendar area for the Material 3 clock **dial**, and a Back returns to the
 * calendar with the picked date intact. One OK commits both. There is deliberately **no keyboard entry**
 * for the time: the dial is the editor and the readout is its display and tap target.
 *
 * This replaces an earlier two-step flow (date → Next → time → OK). The step count matters because the
 * create screen's start-date row is a *primary* interaction, not a rare escape hatch.
 *
 * The component imposes no bounds — the default and any bounds are the caller's concern (an event may
 * start arbitrarily far in the past or the future, capability `event-creation`). A disabled field opens
 * no picker and never invokes [onValueChange].
 */
@Composable
fun AppDateTimeField(
    value: LocalDateTime?,
    onValueChange: (LocalDateTime) -> Unit,
    enabled: Boolean = true,
) {
    var showPicker by remember { mutableStateOf(false) }

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
        Box(Modifier.matchParentSize().clickable(enabled = enabled) { showPicker = true })
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
 * The one-dialog date+time picker: calendar on top, `[HH]:[MM]` readout beneath. Tapping the readout
 * flips [editingTime], swapping the calendar for the dial — the readout stays visible throughout, so the
 * value being edited is never hidden behind the editor.
 *
 * Shared by [AppDateTimeField] and [AppEventStartRow] so the two present the *same* picker; the dialog
 * itself stays private to this module (it is Material 3 all the way down).
 */
@Composable
internal fun DateTimePickerDialog(
    initial: LocalDateTime?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDateTime) -> Unit,
) {
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = initial?.let(::utcMidnightMillis),
    )
    val timeState = rememberTimePickerState(
        initialHour = initial?.hour ?: 0,
        initialMinute = initial?.minute ?: 0,
        is24Hour = true,
    )
    var editingTime by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = dateState.selectedDateMillis ?: return@TextButton
                val d = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC)
                onConfirm(
                    LocalDateTime(
                        d.year,
                        d.month.ordinal + 1,
                        d.day,
                        timeState.hour,
                        timeState.minute,
                    ),
                )
            }) { Text("OK") }
        },
        dismissButton = {
            // Back returns to the calendar WITHOUT discarding the dialog, so a mis-tap on the time
            // readout is one tap to undo rather than a lost date.
            if (editingTime) {
                TextButton(onClick = { editingTime = false }) { Text("Back") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (editingTime) {
                    TimePicker(state = timeState)
                } else {
                    DatePicker(state = dateState, title = null, headline = null, showModeToggle = false)
                }
                TimeReadout(
                    hour = timeState.hour,
                    minute = timeState.minute,
                    onClick = { editingTime = true },
                )
            }
        },
    )
}

/** The `[HH]:[MM]` readout beneath the calendar — the tap target that opens the dial. */
@Composable
private fun TimeReadout(hour: Int, minute: Int, onClick: () -> Unit) {
    fun p(n: Int) = n.toString().padStart(2, '0')
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.semantics { contentDescription = "Edit time" },
        ) {
            Text(
                text = "${p(hour)}:${p(minute)}",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
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
