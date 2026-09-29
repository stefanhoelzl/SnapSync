package app.snapsync.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn

/**
 * The event's date range, picked inline on the create screen (capability `create-event`): a summary of both
 * ends, a month calendar, and the From / Until time wheels — all in view at once, with nothing to open and
 * nothing to confirm. A tap on a day and a settle of a wheel each hand [onChange] the next [EventRange];
 * the rules live in `EventRange.kt`, so this only renders and routes gestures.
 *
 * The last day starts on the start's day and its time starts BLANK: settling either Until wheel sets the
 * time. The first tap on a later day places the last day there; after that a tap starts a new range. An
 * endpoint can also be dragged, and a long press then a sweep selects a new range. [latest] bounds the
 * window's length — days past it are greyed while the last day is pending, and times past it cannot be
 * settled on.
 *
 * Appearance-free like the rest of the design system's API: the caller hands over values and callbacks only.
 */
@Composable
fun AppEventRangePicker(range: EventRange, latest: LatestUntil, note: String, onChange: (EventRange) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RangeEnds(range)
            RangeCalendar(range, latest, onChange)
            RangeTimes(range, latest, onChange)
            Text(text = note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

/** Both ends in words: the start as set, the end as set or as the next thing to do. */
@Composable
private fun RangeEnds(range: EventRange) {
    val end = range.until?.let(::formatStart)
        ?: "${appDateLabel(LocalDateTime(range.endDay, range.from.time))}, pick a time"
    Row(modifier = Modifier.fillMaxWidth()) {
        RangeEnd("Starts", formatStart(range.from), set = true, alignEnd = false)
        RangeEnd("Ends", end, set = range.until != null, alignEnd = true)
    }
}

@Composable
private fun RowScope.RangeEnd(
    caption: String,
    value: String,
    set: Boolean,
    alignEnd: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val align = if (alignEnd) TextAlign.End else TextAlign.Start
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(caption, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, textAlign = align)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (set) scheme.onSurface else scheme.primary,
            textAlign = align,
        )
    }
}

@Composable
private fun RangeCalendar(range: EventRange, latest: LatestUntil, onChange: (EventRange) -> Unit) {
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    var visibleMonth by remember {
        mutableStateOf(LocalDate(range.from.year, range.from.month.ordinal.plus(1), 1))
    }
    MonthHeader(
        month = visibleMonth,
        onPrev = { visibleMonth = visibleMonth.plus(-1L, DateTimeUnit.MONTH) },
        onNext = { visibleMonth = visibleMonth.plus(1L, DateTimeUnit.MONTH) },
    )
    WeekdayHeader()
    Box(modifier = Modifier.rangeDrags(visibleMonth, range, latest, onChange)) {
        RangeCalendarGrid(
            visibleMonth = visibleMonth,
            rangeStart = range.from.date,
            rangeEnd = range.endDay,
            bounds = CalendarBounds(today, ceiling = range.lastPickableDay(latest)),
            onPick = { onChange(range.pickDay(it, latest)) },
        )
    }
}

@Composable
private fun RangeTimes(range: EventRange, latest: LatestUntil, onChange: (EventRange) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SettlingTimeWheels(
            caption = "From",
            time = range.from.time,
            anchor = range.from.time,
            allowed = { range.fromAllowed(it, latest) },
            onHour = { onChange(range.settleFromHour(it, latest)) },
            onMinute = { onChange(range.settleFromMinute(it, latest)) },
        )
        SettlingTimeWheels(
            caption = "Until",
            time = range.untilTime,
            anchor = range.from.time,
            allowed = { range.untilAllowed(it, latest) },
            onHour = { onChange(range.settleUntilHour(it, latest)) },
            onMinute = { onChange(range.settleUntilMinute(it, latest)) },
        )
    }
}
