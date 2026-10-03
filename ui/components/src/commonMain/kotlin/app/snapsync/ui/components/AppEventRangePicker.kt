package app.snapsync.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.delay
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
 * endpoint can also be dragged, and a long press then a sweep selects a new range. [bounds] limit the
 * range — days outside them are greyed, and times outside them cannot be settled on.
 *
 * While the end time is incomplete, its summary is a tap target that calls [onPickEndTime]; each increment of
 * [showEndTime] brings the time wheels into view and briefly outlines the Until wheels (capability
 * `create-event`, "The next missing step leads to where it is done"). Starting to move the Until minutes with
 * the hour blank fills the hour from [currentHour] — the clock's, which this module does not read.
 *
 * Appearance-free like the rest of the design system's API: the caller hands over values and callbacks only.
 */
@Composable
fun AppEventRangePicker(
    range: EventRange,
    bounds: RangeBounds,
    note: String,
    currentHour: () -> Int,
    endTime: EndTimeGuide = EndTimeGuide.NONE,
    onChange: (EventRange) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RangeEditor(range, bounds, currentHour, endTime, onChange)
            Text(text = note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

/**
 * How a caller leads the host to a missing end time: [onPickEndTime] is called when the incomplete end's
 * summary is tapped (`null`: not a tap target), and each new value of [showRequests] brings the time wheels
 * into view and outlines the Until wheels.
 */
class EndTimeGuide(val showRequests: Int, val onPickEndTime: (() -> Unit)?) {
    companion object {
        /** No guidance: the summary is never a tap target and nothing asks for the wheels. */
        val NONE = EndTimeGuide(showRequests = 0, onPickEndTime = null)
    }
}

/**
 * The range picker itself — both ends in words, the calendar, the From / Until wheels — without a frame, so
 * the create screen's card and the join surfaces' dialog ([RangePickerDialog]) are the same component and
 * differ only in their [bounds].
 */
@Composable
internal fun RangeEditor(
    range: EventRange,
    bounds: RangeBounds,
    currentHour: () -> Int,
    endTime: EndTimeGuide = EndTimeGuide.NONE,
    onChange: (EventRange) -> Unit,
) {
    RangeEnds(range, endTime.onPickEndTime)
    RangeCalendar(range, bounds, onChange)
    RangeTimes(range, bounds, currentHour, endTime.showRequests, onChange)
}

/**
 * Both ends in words: the start as set, the end as set or as the next thing to do — and then, given
 * [onPickEndTime], a button that leads there.
 */
@Composable
private fun RangeEnds(range: EventRange, onPickEndTime: (() -> Unit)?) {
    val end = range.until?.let(::formatStart)
        ?: "${appDateLabel(LocalDateTime(range.endDay, range.from.time))}, pick a time"
    Row(modifier = Modifier.fillMaxWidth()) {
        RangeEnd("Starts", formatStart(range.from), set = true, alignEnd = false)
        val toEndTime = onPickEndTime.takeIf { range.until == null }
        RangeEnd("Ends", end, set = range.until != null, alignEnd = true, onClick = toEndTime)
    }
}

@Composable
private fun RowScope.RangeEnd(
    caption: String,
    value: String,
    set: Boolean,
    alignEnd: Boolean,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val align = if (alignEnd) TextAlign.End else TextAlign.Start
    val tappable = onClick?.let { Modifier.clickable(role = Role.Button, onClick = it) } ?: Modifier
    Column(
        modifier = Modifier.weight(1f).then(tappable),
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
private fun RangeCalendar(range: EventRange, bounds: RangeBounds, onChange: (EventRange) -> Unit) {
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
    Box(modifier = Modifier.rangeDrags(visibleMonth, range, bounds, onChange)) {
        RangeCalendarGrid(
            visibleMonth = visibleMonth,
            rangeStart = range.from.date,
            rangeEnd = range.endDay,
            bounds = CalendarBounds(today, floor = bounds.earliest?.date, ceiling = range.lastPickableDay(bounds)),
            onPick = { onChange(range.pickDay(it, bounds)) },
        )
    }
}

@Composable
private fun RangeTimes(
    range: EventRange,
    bounds: RangeBounds,
    currentHour: () -> Int,
    showRequests: Int,
    onChange: (EventRange) -> Unit,
) {
    val requester = remember { BringIntoViewRequester() }
    val highlight = rememberUntilHighlight(showRequests) { requester.bringIntoView() }
    Row(
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(requester),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettlingTimeWheels(
            caption = "From",
            hour = range.from.hour,
            minute = range.from.minute,
            anchor = range.from.time,
            allowed = { range.fromAllowed(it, bounds) },
            onHour = { onChange(range.settleFromHour(it, bounds)) },
            onMinute = { onChange(range.settleFromMinute(it, bounds)) },
        )
        SettlingTimeWheels(
            caption = "Until",
            hour = range.untilHour,
            minute = range.untilMinute,
            anchor = range.from.time,
            allowed = { range.untilAllowed(it, bounds) },
            onHour = { onChange(range.settleUntilHour(it, bounds)) },
            onMinute = { onChange(range.settleUntilMinute(it, bounds, currentHour())) },
            // Moving the minutes with the hour blank means "this hour": the clock's (capability `create-event`).
            onMinuteDragStart = { if (range.untilHour == null) onChange(range.fillUntilHour(currentHour(), bounds)) },
            highlight = highlight,
        )
    }
}

/**
 * The Until wheels' outline, 0 to 1: on each new [requests] value (none at 0) [reveal] scrolls the wheels into
 * view, then the outline rises, holds and fades — or, under reduce motion, simply shows and hides.
 */
@Composable
private fun rememberUntilHighlight(requests: Int, reveal: suspend () -> Unit): Float {
    val reduceMotion = LocalReduceMotion.current
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(requests) {
        if (requests == 0) return@LaunchedEffect
        reveal()
        if (reduceMotion) {
            alpha.snapTo(1f)
            delay(HIGHLIGHT_HOLD_MS + HIGHLIGHT_FADE_MS)
            alpha.snapTo(0f)
        } else {
            alpha.animateTo(1f, tween(HIGHLIGHT_RISE_MS))
            delay(HIGHLIGHT_HOLD_MS)
            alpha.animateTo(0f, tween(HIGHLIGHT_FADE_MS))
        }
    }
    return alpha.value
}

private const val HIGHLIGHT_RISE_MS = 200
private const val HIGHLIGHT_HOLD_MS = 700L
private const val HIGHLIGHT_FADE_MS = 700
