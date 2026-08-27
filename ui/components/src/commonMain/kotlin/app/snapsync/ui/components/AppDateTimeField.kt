package app.snapsync.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

import kotlinx.datetime.todayIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
 * The one-dialog date+time picker: a **hand-drawn** month calendar on top, an inline `HH:MM` time stepper
 * beneath. Both are visible at once — changing the time never hides the calendar, and vice versa — so the
 * value being edited is never behind a mode swap.
 *
 * Drawn entirely from `Box`/`Text` on the frozen scheme tokens (no Material `DatePicker`/`TimePicker`): a
 * 7-column Monday-start grid, the selected day filled with the brand-green circle, today ringed, and every
 * day before [minimum] greyed and inert. All date arithmetic (month lengths, leap years, first weekday)
 * comes from kotlinx-datetime — never hand-rolled day counting.
 *
 * The popup card, heading, month header and weekday strip come from [PickerDialogShell], which this and
 * the range variant both fill in — see there for why it is a `Popup` and not an `AlertDialog`.
 *
 * Used by [AppRangePresetChoices]' per-handle Custom rows (each a single instant within the event window);
 * the range span itself is picked by [DateTimeRangePickerDialog]. The dialog stays internal to this module.
 */
@Composable
internal fun DateTimePickerDialog(
    initial: LocalDateTime?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDateTime) -> Unit,
    minimum: LocalDateTime? = null,
    maximum: LocalDateTime? = null,
) {
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }

    // Seed from the current value, else the floor, else today — never an empty calendar.
    val seed = initial?.date ?: minimum?.date ?: today
    var selectedDate by remember { mutableStateOf(seed) }
    var hour by remember { mutableStateOf(initial?.hour ?: 0) }
    var minute by remember { mutableStateOf(initial?.minute ?: 0) }

    PickerDialogShell(
        seedMonth = seed,
        onDismiss = onDismiss,
        onConfirm = {
            onConfirm(
                LocalDateTime(
                    selectedDate.year,
                    selectedDate.month.ordinal.plus(1),
                    selectedDate.day,
                    hour,
                    minute,
                ),
            )
        },
        calendar = { visibleMonth ->
            CalendarGrid(
                visibleMonth = visibleMonth,
                selected = selectedDate,
                today = today,
                floor = minimum?.date,
                ceiling = maximum?.date,
                onPick = { selectedDate = it },
            )
        },
        wheels = {
            TimeWheels(
                hour = hour,
                minute = minute,
                onHour = { hour = it },
                onMinute = { minute = it },
            )
        },
    )
}

/**
 * The **dual-handle range** variant of the one-dialog picker (capability `design-system`): the same
 * hand-drawn single-month calendar, but the user taps a **start day** then an **end day** to select an
 * inclusive `[from, until]` span, with **two** time-wheel pairs — a **From time** and an **Until time** —
 * beneath it. One confirmation commits the whole span.
 *
 * Selection is a three-tap cycle: with a complete range showing, the next tap **resets** to a new start
 * (span cleared); the tap after that sets the end (a same-day range is that same day tapped twice). A tap
 * **earlier** than the pending start moves the start earlier rather than forming an inverted range — so an
 * `until` before `from` is unreachable. Tapping a new day span changes **only the dates**; the two wheel
 * times are preserved (they are independent state).
 *
 * The optional `[minimum, maximum]` **window** (either bound absent) greys days outside it and constrains
 * the picker; the create surface passes **no** window, so any day/time is selectable and only `start < end`
 * is required (the caller enforces that). A day-grain calendar cannot forbid an out-of-window *hour* on a
 * boundary day, so the confirmed instants are additionally coerced into the window here.
 */
@Composable
internal fun DateTimeRangePickerDialog(
    initialFrom: LocalDateTime,
    initialUntil: LocalDateTime,
    minimum: LocalDateTime?,
    maximum: LocalDateTime?,
    onDismiss: () -> Unit,
    onConfirm: (from: LocalDateTime, until: LocalDateTime) -> Unit,
) {
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }

    // The span. `endDate == null` means mid-selection (only the start is placed); it resolves to the start
    // for both the highlight and the confirmed value, so a single-tap day is a valid same-day range.
    var startDate by remember { mutableStateOf(initialFrom.date) }
    var endDate by remember { mutableStateOf<LocalDate?>(initialUntil.date) }
    var fromHour by remember { mutableStateOf(initialFrom.hour) }
    var fromMinute by remember { mutableStateOf(initialFrom.minute) }
    var untilHour by remember { mutableStateOf(initialUntil.hour) }
    var untilMinute by remember { mutableStateOf(initialUntil.minute) }

    fun onPick(picked: LocalDate) {
        if (endDate != null) {
            // A complete range is showing → begin a fresh selection at the tapped day.
            startDate = picked
            endDate = null
        } else if (picked < startDate) {
            // Mid-selection, tapped before the start → move the start earlier (never an inverted range).
            startDate = picked
        } else {
            // Mid-selection, tapped on/after the start → close the span.
            endDate = picked
        }
    }

    PickerDialogShell(
        seedMonth = initialFrom.date,
        onDismiss = onDismiss,
        onConfirm = {
            val eDate = endDate ?: startDate
            var from = LocalDateTime(
                startDate.year, startDate.month.ordinal.plus(1), startDate.day,
                fromHour, fromMinute,
            )
            var until = LocalDateTime(
                eDate.year, eDate.month.ordinal.plus(1), eDate.day,
                untilHour, untilMinute,
            )
            // Coerce each bound into the window (the calendar cannot forbid a boundary-day hour outside it).
            if (minimum != null && from < minimum) from = minimum
            if (maximum != null && until > maximum) until = maximum
            onConfirm(from, until)
        },
        calendar = { visibleMonth ->
            RangeCalendarGrid(
                visibleMonth = visibleMonth,
                rangeStart = startDate,
                rangeEnd = endDate ?: startDate,
                today = today,
                floor = minimum?.date,
                ceiling = maximum?.date,
                onPick = { onPick(it) },
            )
        },
        // From and Until times sit side by side — two captioned wheel pairs sharing the row.
        wheels = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    WheelCaption("From time")
                    TimeWheels(
                        hour = fromHour,
                        minute = fromMinute,
                        onHour = { fromHour = it },
                        onMinute = { fromMinute = it },
                        hourDescription = "From hour",
                        minuteDescription = "From minute",
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    WheelCaption("Until time")
                    TimeWheels(
                        hour = untilHour,
                        minute = untilMinute,
                        onHour = { untilHour = it },
                        onMinute = { untilMinute = it },
                        hourDescription = "Until hour",
                        minuteDescription = "Until minute",
                    )
                }
            }
        },
    )
}

/**
 * The frame both pickers are: the pane-centred popup card, its heading, the month header and weekday
 * strip, then the caller's calendar and wheels, then Cancel / OK. Only those two slots ever differed —
 * everything around them was written out twice, once per dialog, down to the `340.dp` and the `24.dp`
 * corner.
 *
 * [visibleMonth] lives here rather than in either caller because it belongs to the header that scrolls it,
 * not to what the calendar does with it; both dialogs wired an identical pair of month-stepping lambdas to
 * reach it. The calendar slot receives it as a parameter, so a caller reads the month without owning it.
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
    seedMonth: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    calendar: @Composable (visibleMonth: LocalDate) -> Unit,
    wheels: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var visibleMonth by remember {
        mutableStateOf(LocalDate(seedMonth.year, seedMonth.month.ordinal.plus(1), 1))
    }

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
                        text = "Date & time",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = scheme.onSurface,
                        // Announce the dialog's title as a heading so VoiceOver states what opened.
                        modifier = Modifier.semantics { heading() },
                    )
                    MonthHeader(
                        month = visibleMonth,
                        // visibleMonth is always a first-of-month, so month arithmetic keeps day == 1.
                        onPrev = { visibleMonth = visibleMonth.plus(-1L, DateTimeUnit.MONTH) },
                        onNext = { visibleMonth = visibleMonth.plus(1L, DateTimeUnit.MONTH) },
                    )
                    WeekdayHeader()
                    calendar(visibleMonth)
                    wheels()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.weight(1f)) { SecondaryButton(label = "Cancel", onClick = onDismiss) }
                        Box(Modifier.weight(1f)) { PrimaryButton(label = "OK", onClick = onConfirm) }
                    }
                }
            }
        }
    }
}

/** The small caption above each of the range dialog's two wheel pairs. */
@Composable
private fun WheelCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
