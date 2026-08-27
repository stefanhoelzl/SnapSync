package app.snapsync.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
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


// `internal` rather than `private` throughout: Kotlin's top-level `private` is FILE-private, and this
// widget was split out of an 887-line file. Everything another split file reaches is widened to module
// scope and no further — `:ui:components` is the design system, the same audience these had before.
//
// The calendar grids the date pickers open onto (capability `event-creation-ui`): a range-aware grid
// with its day cell, a single-date grid with its own, and the month/weekday chrome both share.

/**
 * The month grid in **range mode**: the two endpoint days are filled with the brand-green circle, the days
 * strictly between them wear a lighter `primaryContainer` band, and days outside the `[floor, ceiling]`
 * window are greyed and inert. Layout mirrors [CalendarGrid]; only the per-day treatment differs.
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
        verticalArrangement = Arrangement.spacedBy(2.dp),
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
                                isStart = date == rangeStart,
                                isEnd = date == rangeEnd,
                                inRange = date > rangeStart && date < rangeEnd,
                                isToday = date == bounds.today,
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
    isStart: Boolean,
    isEnd: Boolean,
    inRange: Boolean,
    isToday: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val endpoint = isStart || isEnd
    val fill = if (endpoint) scheme.primary else Color.Transparent
    val ring = if (isToday && !endpoint && !inRange) scheme.primary else Color.Transparent
    val textColor = when {
        endpoint -> scheme.onPrimary
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.35f)
        else -> scheme.onSurface
    }
    val label = buildString {
        append(weekdayName(date))
        append(' ')
        append(date.day)
        append(' ')
        append(monthName(date.month.ordinal.plus(1)))
        append(' ')
        append(date.year)
        if (isToday) append(", today")
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // The connecting band for strictly-between days spans the full cell width so the range reads as
            // one continuous stripe rather than isolated dots.
            .background(if (inRange) scheme.primaryContainer else Color.Transparent)
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
                .size(38.dp)
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
                    fontWeight = if (endpoint || isToday) FontWeight.Bold else FontWeight.Normal,
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
        ChevronButton(Icons.Filled.KeyboardArrowLeft, "Previous month", onPrev)
        Text(
            text = "${monthName(month.month.ordinal.plus(1))} ${month.year}",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        ChevronButton(Icons.Filled.KeyboardArrowRight, "Next month", onNext)
    }
}

/** A 36dp square chevron tap target — muted tint, the calendar's quiet navigation. */
@Composable
private fun ChevronButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The Monday-start weekday labels, one per column, aligned with the grid beneath. */
@Composable
internal fun WeekdayHeader() {
    // One merged, static node so assistive tech reads the row once ("Mo Tu We …") rather than seven
    // stray one-letter stops between the month header and the day buttons.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "Weekdays, Monday to Sunday" },
    ) {
        for (label in listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")) {
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
 * The month grid. Leading blanks come from the first-of-month's weekday (Monday = ordinal 0), the row
 * count from the month's real length — both from kotlinx-datetime, so leap years and month boundaries are
 * never hand-counted. Weeks are laid out as `Row`s of seven weight-1 cells, so the grid fits any dialog
 * width (this is why it does not clip on a 390pt pane the way the M3 `DatePicker` did).
 */
@Composable
internal fun CalendarGrid(
    visibleMonth: LocalDate,
    selected: LocalDate,
    bounds: CalendarBounds,
    onPick: (LocalDate) -> Unit,
) {
    val firstOfMonth = LocalDate(visibleMonth.year, visibleMonth.month.ordinal.plus(1), 1)
    val leadingBlanks = firstOfMonth.dayOfWeek.ordinal // Monday == 0
    val daysInMonth = firstOfMonth.daysUntil(firstOfMonth.plus(1L, DateTimeUnit.MONTH))

    val totalCells = leadingBlanks + daysInMonth
    val rows = (totalCells + ROUND_UP_TO_WHOLE_WEEK) / DAYS_PER_WEEK

    // selectableGroup marks the days as one single-selection set, so assistive tech announces each day as
    // "one of N" within the grid rather than as isolated buttons.
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (row in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until DAYS_PER_WEEK) {
                    val cellIndex = row * DAYS_PER_WEEK + col
                    val dayNumber = cellIndex - leadingBlanks + 1
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (dayNumber in 1..daysInMonth) {
                            val date = LocalDate(firstOfMonth.year, firstOfMonth.month.ordinal.plus(1), dayNumber)
                            DayCell(
                                date = date,
                                selected = date == selected,
                                isToday = date == bounds.today,
                                // A day is selectable only inside the supplied window (either bound may be
                                // absent): at or after the bounds.floor AND at or before the bounds.ceiling.
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
 * One day: a 40dp circle target. Selected → the brand-green fill with `onPrimary` text; today (unselected)
 * → a hairline brand ring; a day below the floor → muted and inert. Undrawn otherwise, so the grid reads
 * as numbers on the card, not a table of buttons.
 *
 * **Semantics.** The whole cell is one [selectable] ([Role.Button]) carrying the FULL date as its
 * contentDescription ("Monday 20 July 2026") — VoiceOver would otherwise hear a bare "20". The chosen day
 * reports `selected`; today folds "today" into its description; a below-floor day stays in the tree as a
 * **disabled** button (present, not silent) so a guest hears why it can't be picked. The [selectable] sits
 * on an outer box that fills the grid slot, so the hit area spans the whole cell (the visual circle stays
 * 38dp) — the standard "extend the touch target, not the paint" pattern. A literal 44dp cell is impossible
 * here: seven of them overflow the 340dp dialog, and growing the row would move the resting grid.
 */
@Composable
private fun DayCell(
    date: LocalDate,
    selected: Boolean,
    isToday: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (selected) scheme.primary else Color.Transparent
    val ring = if (isToday && !selected) scheme.primary else Color.Transparent
    val textColor = when {
        selected -> scheme.onPrimary
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.35f)
        else -> scheme.onSurface
    }
    val label = buildString {
        append(weekdayName(date))
        append(' ')
        append(date.day)
        append(' ')
        append(monthName(date.month.ordinal.plus(1)))
        append(' ')
        append(date.year)
        if (isToday) append(", today")
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
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
                .size(38.dp)
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
                    fontWeight = if (selected || isToday) FontWeight.Bold else FontWeight.Normal,
                ),
                color = textColor,
            )
        }
    }
}

/**
 * What a calendar month is bounded by: today (ringed), and the selectable window's ends.
 *
 * The three travel together — both grids took all three and neither ever varies one without the others —
 * and a `floor`/`ceiling` pair separated from the `today` it is compared against is easy to hand over in
 * the wrong order, since all three are `LocalDate`.
 *
 * A null bound means unbounded on that side, not "unknown": the create surface passes no window at all.
 */
internal class CalendarBounds(
    val today: LocalDate,
    val floor: LocalDate? = null,
    val ceiling: LocalDate? = null,
) {
    /**
     * Whether a day is selectable. Both grids spelled this comparison out separately, which is two places
     * to get an inclusive bound wrong; a null bound is unbounded, so it admits.
     */
    fun allows(date: LocalDate): Boolean =
        (floor == null || date >= floor) && (ceiling == null || date <= ceiling)
}
