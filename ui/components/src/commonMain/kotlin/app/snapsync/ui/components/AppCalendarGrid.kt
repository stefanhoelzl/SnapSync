package app.snapsync.ui.components

import androidx.compose.foundation.background
import kotlinx.datetime.LocalTime
import kotlinx.datetime.LocalDateTime
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import app.snapsync.ui.components.resources.Res
import app.snapsync.ui.components.resources.calendar_day_today
import app.snapsync.ui.components.resources.calendar_next_month
import app.snapsync.ui.components.resources.calendar_previous_month
import app.snapsync.ui.components.resources.calendar_weekdays
import org.jetbrains.compose.resources.stringResource


// `internal` rather than `private` throughout: Kotlin's top-level `private` is FILE-private, and this
// widget was split out of an 887-line file. Everything another split file reaches is widened to module
// scope and no further — `:ui:components` is the design system, the same audience these had before.
//
// The range calendar (capabilities `create-event`, `join-event`): the range-aware grid with its day cell,
// and the month/weekday chrome above it.

/** A day's circle: small enough for six weeks and the wheels beneath to fit a small phone's form. */
private val DAY_CIRCLE = 32.dp

/**
 * The month grid in **range mode**: the two endpoint days are filled with the brand-green circle, the days
 * strictly between them wear a lighter `primaryContainer` band, and days outside the `[floor, ceiling]`
 * window are greyed and inert: a 7-column Monday-start grid of whole weeks.
 */
@Composable
internal fun RangeCalendarGrid(
    visibleMonth: LocalDate,
    rangeStart: LocalDate,
    rangeEnd: LocalDate,
    bounds: CalendarBounds,
    onPick: (LocalDate) -> Unit,
) {
    val firstOfMonth = LocalDate(visibleMonth.year, visibleMonth.month.ordinal.plus(1), 1)
    val leadingBlanks = firstOfMonth.dayOfWeek.ordinal // Monday == 0
    val daysInMonth = firstOfMonth.daysUntil(firstOfMonth.plus(1L, DateTimeUnit.MONTH))
    val rows = (leadingBlanks + daysInMonth + ROUND_UP_TO_WHOLE_WEEK) / DAYS_PER_WEEK

    Column(
        modifier = Modifier.selectableGroup(),
        // Rows touch: the circles' own inset is the gap, which keeps six weeks short enough to leave the time
        // wheels in view on a small phone.
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        for (row in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until DAYS_PER_WEEK) {
                    val dayNumber = row * DAYS_PER_WEEK + col - leadingBlanks + 1
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (dayNumber in 1..daysInMonth) {
                            val date = LocalDate(firstOfMonth.year, firstOfMonth.month.ordinal.plus(1), dayNumber)
                            RangeDayCell(
                                date = date,
                                position = DayInRange(
                                    isStart = date == rangeStart,
                                    isEnd = date == rangeEnd,
                                    inRange = date > rangeStart && date < rangeEnd,
                                    isToday = date == bounds.today,
                                ),
                                enabled = bounds.allows(date),
                                onClick = { onPick(date) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One day in range mode. An endpoint (start or end) is the filled brand circle; a strictly-between day gets
 * the `primaryContainer` band across the whole cell; a below/above-window day is muted and inert. The whole
 * cell is one [selectable] carrying the FULL date as its contentDescription, reporting `selected` on either
 * endpoint — the same accessibility contract [DayCell] carries.
 */
@Composable
private fun RangeDayCell(
    date: LocalDate,
    position: DayInRange,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val endpoint = position.isStart || position.isEnd
    val fill = if (endpoint) scheme.primary else Color.Transparent
    val ring = if (position.isToday && !endpoint && !position.inRange) scheme.primary else Color.Transparent
    val textColor = when {
        endpoint -> scheme.onPrimary
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.35f)
        else -> scheme.onSurface
    }
    val spoken = LocalDateFormats.current.format(LocalDateTime(date, LocalTime(0, 0)), "yMMMMEEEEd")
    val label = if (position.isToday) stringResource(Res.string.calendar_day_today, spoken) else spoken
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // The connecting band for strictly-between days spans the full cell width so the range reads as
            // one continuous stripe rather than isolated dots.
            .background(if (position.inRange) scheme.primaryContainer else Color.Transparent)
            .selectable(
                selected = endpoint,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .padding(1.dp)
                .size(DAY_CIRCLE)
                .clip(CircleShape)
                .background(fill)
                .border(
                    width = if (ring != Color.Transparent) 1.5.dp else 0.dp,
                    color = ring,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.day.toString(),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (endpoint || position.isToday) FontWeight.Bold else FontWeight.Normal,
                ),
                color = textColor,
            )
        }
    }
}

/**
 * Centres the picker horizontally on the anchor (a pane-wide `Box`, so this is the pane's centre) and
 * vertically within the host window, clamped so the card never leaves the window. On device the window and
 * the pane coincide, so this is a plain centred dialog; in the multi-pane harness it keeps the card inside
 * the phone pane instead of the host window's centre.
 */
internal class PaneCenteredProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val y = (windowSize.height - popupContentSize.height) / 2
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        return IntOffset(x.coerceIn(0, maxX), y.coerceAtLeast(0))
    }
}

/** The month name + year, flanked by the prev/next chevrons that page [visibleMonth]. */
@Composable
internal fun MonthHeader(month: LocalDate, onPrev: () -> Unit, onNext: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val previous = stringResource(Res.string.calendar_previous_month)
        ChevronButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, previous, onPrev)
        Text(
            text = LocalDateFormats.current.format(LocalDateTime(month, LocalTime(0, 0)), "yMMMM"),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        val next = stringResource(Res.string.calendar_next_month)
        ChevronButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, next, onNext)
    }
}

/** A square chevron tap target the height of a day row — muted tint, the calendar's quiet navigation. */
@Composable
private fun ChevronButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(DAY_CIRCLE)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The Monday-start weekday labels, one per column, aligned with the grid beneath — the locale's narrow names. */
@Composable
internal fun WeekdayHeader() {
    val dates = LocalDateFormats.current
    val week = (0 until DAYS_PER_WEEK).map { LocalDateTime(A_MONDAY.plus(it, DateTimeUnit.DAY), LocalTime(0, 0)) }
    val spoken = stringResource(
        Res.string.calendar_weekdays,
        dates.format(week.first(), "EEEE"),
        dates.format(week.last(), "EEEE"),
    )
    // One merged, static node so assistive tech reads the row once ("Weekdays, Monday to Sunday") rather than
    // seven stray one-letter stops between the month header and the day buttons.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        for (label in week.map { dates.format(it, "EEEEE") }) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * What a calendar month is bounded by: today (ringed), and the selectable window's ends.
 *
 * The three travel together: a `floor`/`ceiling` pair separated from the `today` it is compared against is
 * easy to hand over in the wrong order, since all three are `LocalDate`.
 *
 * A null bound means unbounded on that side, not "unknown": the create surface has no window at all.
 */
internal class CalendarBounds(
    val today: LocalDate,
    val floor: LocalDate? = null,
    val ceiling: LocalDate? = null,
) {
    /**
     * Whether a day is selectable; a null bound is unbounded, so it admits.
     */
    fun allows(date: LocalDate): Boolean =
        (floor == null || date >= floor) && (ceiling == null || date <= ceiling)
}

/**
 * Where a day sits relative to the selected span — which is what decides how the cell is drawn: the two
 * ends get the filled pill, the days between get the connecting band, today gets its ring.
 *
 * Four booleans that are only ever computed together from the same span, and passing them as four
 * adjacent `Boolean`s made their order the only thing keeping them apart.
 */
internal class DayInRange(
    val isStart: Boolean,
    val isEnd: Boolean,
    val inRange: Boolean,
    val isToday: Boolean,
)

/** Any Monday: the header names a week's days from it. */
private val A_MONDAY = LocalDate(2024, 1, 1)
