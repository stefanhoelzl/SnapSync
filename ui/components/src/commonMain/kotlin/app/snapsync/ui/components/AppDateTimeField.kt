package app.snapsync.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn

/**
 * A semantic date+time input — the app's temporal picker. Appearance-free: it carries the current
 * [value] (a plain [LocalDateTime], the picked **local** wall-clock instant, or `null` when unset), a
 * change callback, and an [enabled] flag; no colors, shapes, text styles, or `Modifier`, and no Material
 * 3 type in the signature. The hand-drawn calendar + time stepper (and the dialog frame) live inside the
 * component (design-system containment rule).
 *
 * Tapping the field opens **one** dialog carrying both the month calendar and an inline `HH:MM` time
 * stepper — both always visible, no mode swap. One OK commits both. There is deliberately **no keyboard
 * entry**: the calendar is the date editor and the steppers are the time editor.
 *
 * [minimum] is an optional **floor**: when set, the picker greys out every day before the floor's day
 * AND the dialog coerces the committed value up to the floor (a day-grain calendar cannot forbid an
 * earlier *hour* on the floor's own day), so this field never surfaces a value below it. The create
 * screen passes `null` (an event may start arbitrarily far in the past); the join surface's custom cutoff
 * passes the event start (the backend silently raises anything earlier, capability
 * `photo-selection-policy`). A disabled field opens no picker and never invokes [onValueChange].
 */
@Composable
fun AppDateTimeField(
    value: LocalDateTime?,
    onValueChange: (LocalDateTime) -> Unit,
    enabled: Boolean = true,
    minimum: LocalDateTime? = null,
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
        // The whole field reads as one button that opens the picker (the read-only text field alone would
        // announce as an inert text field). contentDescription carries the current value, or a prompt when
        // unset, so VoiceOver states what tapping edits.
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = { showPicker = true },
                )
                .semantics {
                    contentDescription = value?.let { "Date and time, ${formatLocal(it)}" }
                        ?: "Date and time, not set"
                },
        )
    }

    if (showPicker) {
        DateTimePickerDialog(
            initial = value,
            minimum = minimum,
            onDismiss = { showPicker = false },
            onConfirm = {
                showPicker = false
                // Coerce up to the floor: the calendar greys earlier *days*, but the floor may fall
                // mid-day, and the stepper can still land on an earlier hour of the floor's own day.
                onValueChange(if (minimum != null && it < minimum) minimum else it)
            },
        )
    }
}

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
 * **Why a `Popup`, not an `AlertDialog`.** An M3 dialog is a *window-centered* overlay: on a real phone the
 * window IS the 390pt screen, so it centers fine, but the multi-pane desktop harness embeds the phone pane
 * in a much wider host window, and a window-centered dialog then overflows the pane's right edge (which is
 * exactly why the old M3 `DatePicker` clipped there). This picker instead renders in-tree as a `Popup`
 * positioned centered **within the pane** — the enclosing full-width anchor gives [PaneCenteredProvider]
 * the pane's own bounds — so the full picker is visible at 390pt on device and in the harness alike.
 *
 * Shared by [AppDateTimeField], [AppEventStartRow], and [AppCutoffChoices] so the surfaces present the
 * *same* picker; the dialog stays private to this module.
 */
@Composable
internal fun DateTimePickerDialog(
    initial: LocalDateTime?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDateTime) -> Unit,
    minimum: LocalDateTime? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }

    // Seed from the current value, else the floor, else today — never an empty calendar.
    val seed = initial?.date ?: minimum?.date ?: today
    var selectedDate by remember { mutableStateOf(seed) }
    var visibleMonth by remember { mutableStateOf(LocalDate(seed.year, seed.month.ordinal.plus(1), 1)) }
    var hour by remember { mutableStateOf(initial?.hour ?: 0) }
    var minute by remember { mutableStateOf(initial?.minute ?: 0) }

    val floorDate = minimum?.date

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
                    CalendarGrid(
                        visibleMonth = visibleMonth,
                        selected = selectedDate,
                        today = today,
                        floor = floorDate,
                        onPick = { selectedDate = it },
                    )
                    TimeWheels(
                        hour = hour,
                        minute = minute,
                        onHour = { hour = it },
                        onMinute = { minute = it },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.weight(1f)) { SecondaryButton(label = "Cancel", onClick = onDismiss) }
                        Box(Modifier.weight(1f)) {
                            PrimaryButton(
                                label = "OK",
                                onClick = {
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
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Centres the picker horizontally on the anchor (a pane-wide `Box`, so this is the pane's centre) and
 * vertically within the host window, clamped so the card never leaves the window. On device the window and
 * the pane coincide, so this is a plain centred dialog; in the multi-pane harness it keeps the card inside
 * the phone pane instead of the host window's centre.
 */
private class PaneCenteredProvider : PopupPositionProvider {
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
private fun MonthHeader(month: LocalDate, onPrev: () -> Unit, onNext: () -> Unit) {
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
private fun WeekdayHeader() {
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
private fun CalendarGrid(
    visibleMonth: LocalDate,
    selected: LocalDate,
    today: LocalDate,
    floor: LocalDate?,
    onPick: (LocalDate) -> Unit,
) {
    val firstOfMonth = LocalDate(visibleMonth.year, visibleMonth.month.ordinal.plus(1), 1)
    val leadingBlanks = firstOfMonth.dayOfWeek.ordinal // Monday == 0
    val daysInMonth = firstOfMonth.daysUntil(firstOfMonth.plus(1L, DateTimeUnit.MONTH))

    val totalCells = leadingBlanks + daysInMonth
    val rows = (totalCells + 6) / 7

    // selectableGroup marks the days as one single-selection set, so assistive tech announces each day as
    // "one of N" within the grid rather than as isolated buttons.
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (row in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val cellIndex = row * 7 + col
                    val dayNumber = cellIndex - leadingBlanks + 1
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (dayNumber in 1..daysInMonth) {
                            val date = LocalDate(firstOfMonth.year, firstOfMonth.month.ordinal.plus(1), dayNumber)
                            DayCell(
                                date = date,
                                selected = date == selected,
                                isToday = date == today,
                                enabled = floor == null || date >= floor,
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

/** The wheel row geometry: three visible rows keeps the dialog compact under the calendar. */
private val WheelRowHeight = 38.dp
private const val WheelVisibleRows = 3

/**
 * The time as a pair of **snapping wheels** — hour and minute — in the same recessed well the ±1
 * steppers used to occupy. Always visible: changing the time never hides the calendar.
 *
 * Wheels replaced the steppers because ±1 taps made a distant time absurd (14:00 → 19:45 was ~20 taps
 * with nothing to accelerate). Time-of-day is also the one place a wheel is unambiguously the platform
 * idiom (tiny bounded ranges, no month-jumping), so the imitation-physics risk that argued against a
 * date wheel does not apply. The wheel machinery is the sibling wheel-variant's, verified there: snap
 * fling + settle correction, tap-a-row-to-centre, reduce-motion snaps instantly.
 */
@Composable
private fun TimeWheels(
    hour: Int,
    minute: Int,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background)
            .height(WheelRowHeight * WheelVisibleRows),
        contentAlignment = Alignment.Center,
    ) {
        // The centre reading line, drawn behind the wheels so the emphasised row reads *on* it.
        SelectionBand()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Each wheel labels itself and states its current value, so VoiceOver reads "Hour, 22" / "Minute,
            // 08" instead of two unlabelled columns of numbers. Semantics are non-merging, so the rows below
            // stay individually tappable (tap-to-centre is the wheel's only click-driven affordance).
            WheelColumn(
                count = 24,
                initialIndex = hour,
                onIndexChange = onHour,
                label = { it.toString().padStart(2, '0') },
                modifier = Modifier.width(52.dp).semantics {
                    contentDescription = "Hour"
                    stateDescription = hour.toString().padStart(2, '0')
                },
            )
            // A static colon on the reading line marks HH:MM.
            Text(
                text = ":",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            WheelColumn(
                count = 60,
                initialIndex = minute,
                onIndexChange = onMinute,
                label = { it.toString().padStart(2, '0') },
                modifier = Modifier.width(52.dp).semantics {
                    contentDescription = "Minute"
                    stateDescription = minute.toString().padStart(2, '0')
                },
            )
        }
    }
}

/**
 * The centre reading line: a one-row-tall `surfaceVariant` bar between two `outlineVariant` hairlines.
 */
@Composable
private fun SelectionBand() {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
    ) {
        HorizontalDivider(thickness = 1.dp, color = scheme.outlineVariant)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(WheelRowHeight)
                .padding(horizontal = 8.dp)
                .background(scheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(9.dp)),
        )
        HorizontalDivider(thickness = 1.dp, color = scheme.outlineVariant)
    }
}

/**
 * One snapping wheel: a [LazyColumn] with a fixed row height and one-row top/bottom padding so the
 * current item rests on the centre reading line. Centre index derives from the scroll position; a snap
 * fling settles to a row, and a settle-correction realigns after a slow drag (which carries no fling
 * velocity). Tapping an off-centre row brings it to the reading line — the iOS wheel affordance, and the
 * only lever a click-driven harness has. Honours [LocalReduceMotion] by snapping instantly.
 */
@Composable
private fun WheelColumn(
    count: Int,
    initialIndex: Int,
    onIndexChange: (Int) -> Unit,
    label: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val reduceMotion = LocalReduceMotion.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val density = LocalDensity.current
    val rowPx = with(density) { WheelRowHeight.toPx() }
    val padRows = (WheelVisibleRows - 1) / 2

    // The row currently on the centre reading line, from the scroll position.
    val centerIndex by remember {
        derivedStateOf {
            val settled = listState.firstVisibleItemScrollOffset / rowPx
            (listState.firstVisibleItemIndex + settled.roundToInt()).coerceIn(0, count - 1)
        }
    }

    LaunchedEffect(centerIndex) { onIndexChange(centerIndex) }

    // A fling snaps via the behaviour below; a slow drag does not, so realign when motion ends.
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            if (reduceMotion) listState.scrollToItem(centerIndex) else listState.animateScrollToItem(centerIndex)
        }
    }

    val snapFling = rememberSnapFlingBehavior(lazyListState = listState)
    val decayFling = ScrollableDefaults.flingBehavior()
    val scope = rememberCoroutineScope()

    LazyColumn(
        state = listState,
        flingBehavior = if (reduceMotion) decayFling else snapFling,
        contentPadding = PaddingValues(vertical = WheelRowHeight * padRows),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.height(WheelRowHeight * WheelVisibleRows),
    ) {
        items(count) { i ->
            val distance = abs(i - centerIndex)
            val alpha = when (distance) {
                0 -> 1f
                1 -> 0.5f
                else -> 0.25f
            }
            Box(
                modifier = Modifier
                    .height(WheelRowHeight)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                    ) {
                        scope.launch {
                            if (reduceMotion) listState.scrollToItem(i) else listState.animateScrollToItem(i)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(i),
                    style = if (distance == 0) {
                        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    color = scheme.onSurface.copy(alpha = alpha),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Full weekday names for a day cell's spoken date. `dayOfWeek.ordinal` is Monday = 0. */
private fun weekdayName(date: LocalDate): String = listOf(
    "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
)[date.dayOfWeek.ordinal]

/** Full month names for the calendar header. */
private fun monthName(monthNumber: Int): String = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)[monthNumber - 1]

/** A readable `yyyy-MM-dd HH:mm` rendering of the picked local value (display only, not the cutoff). */
private fun formatLocal(value: LocalDateTime): String {
    fun p(n: Int, width: Int) = n.toString().padStart(width, '0')
    return "${p(value.year, 4)}-${p(value.month.ordinal.plus(1), 2)}-${p(value.day, 2)} " +
        "${p(value.hour, 2)}:${p(value.minute, 2)}"
}
