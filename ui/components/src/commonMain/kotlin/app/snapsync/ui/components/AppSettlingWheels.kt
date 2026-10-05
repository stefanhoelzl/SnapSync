package app.snapsync.ui.components

import androidx.compose.foundation.clickable
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime

// The range picker's time wheels (capabilities `create-event`, `join-event`). They are CONTROLLED: the value
// lives with the caller, a wheel reports only where the HOST made it come to rest, and it follows the value
// when the caller moves it (a settle the rules pulled back, a start that moved under a blank end). That is
// what lets an end time stay blank until touched, and what makes a disallowed time unreachable instead of
// refused.

private const val HOURS_PER_DAY = 24
private const val MINUTES_PER_HOUR = 60
private const val BLOCKED_ALPHA = 0.18f
private const val BLANK_LABEL = "--"
private const val BLANK_STATE = "not set"

/** The wheel row geometry: three visible rows keeps the dialog compact under the calendar. */
internal val WheelRowHeight = 38.dp
internal const val WHEEL_VISIBLE_ROWS = 3

/**
 * The centre reading line: a one-row-tall `surfaceVariant` bar between two `outlineVariant` hairlines.
 */
@Composable
internal fun SelectionBand() {
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
 * Which row is on the centre reading line, derived from the scroll position.
 *
 * Its own function because it is the wheel's one piece of arithmetic — the pixel offset a partially
 * scrolled list reports, converted to whole rows — and it answers a different question from everything
 * around it: that code moves the list, this reads where the list came to rest.
 */
@Composable
internal fun rememberCenteredRow(listState: LazyListState, count: Int): Int {
    val rowPx = with(LocalDensity.current) { WheelRowHeight.toPx() }
    val centerIndex by remember {
        derivedStateOf {
            val settled = listState.firstVisibleItemScrollOffset / rowPx
            (listState.firstVisibleItemIndex + settled.roundToInt()).coerceIn(0, count - 1)
        }
    }
    return centerIndex
}

/**
 * What one end's wheels are called: the [shown] caption above them ("Starts"), and the [spoken] name a screen
 * reader gives the two wheels ("Start hour", "Start minute"). One value, so a wheel pair carries one label.
 */
internal data class WheelCaption(val shown: String, val spoken: String = shown)

/**
 * One end's time: its caption over an hour wheel and a minute wheel that scroll and settle independently.
 *
 * A `null` [hour] or [minute] is BLANK: that wheel sits over [anchor] (so the first flick moves from a familiar
 * place) but its reading line shows no value until the host drags it, and its first settle sets one. The hour
 * and the minute are blank independently. [allowed] decides which rows can be settled on; the rest are struck
 * through. [onMinuteDragStart] hears the host start dragging the minute wheel — where a blank hour is filled.
 * [highlight] (0 to 1) outlines the wheels in the accent colour, to show the host where a missing time is set.
 */
@Composable
internal fun RowScope.SettlingTimeWheels(
    caption: WheelCaption,
    hour: Int?,
    minute: Int?,
    anchor: LocalTime,
    allowed: (LocalTime) -> Boolean,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit,
    onMinuteDragStart: () -> Unit = {},
    highlight: Float = 0f,
) {
    val shownHour = hour ?: anchor.hour
    val shape = RoundedCornerShape(12.dp)
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = caption.shown,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.background)
                // The "here is where you set it" mark ([highlight] 0..1): an accent outline, gone at 0.
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = highlight), shape)
                .height(WheelRowHeight * WHEEL_VISIBLE_ROWS),
            contentAlignment = Alignment.Center,
        ) {
            SelectionBand()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SettlingWheel(
                    wheel = WheelSpec(HOURS_PER_DAY, shownHour, blank = hour == null, "${caption.spoken} hour"),
                    allowed = { h -> hourHasAllowedMinute(h, allowed) },
                    onSettle = onHour,
                )
                Text(text = ":", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                SettlingWheel(
                    wheel = WheelSpec(
                        MINUTES_PER_HOUR, minute ?: anchor.minute, blank = minute == null, "${caption.spoken} minute",
                    ),
                    allowed = { m -> allowed(LocalTime(shownHour, m)) },
                    onSettle = onMinute,
                    onDragStart = onMinuteDragStart,
                )
            }
        }
    }
}

/** What one wheel shows: its size, the value on the reading line, and whether that value is really set. */
private class WheelSpec(
    val count: Int,
    val value: Int,
    val blank: Boolean,
    val description: String,
)

/**
 * One controlled wheel. It reports [onSettle] when the host brings it to rest — by a drag or fling that
 * ends off the current value, by any drag at all on a blank wheel (landing back on the anchor still means
 * "this one"), or by tapping a row — and scrolls itself to [WheelSpec.value] whenever that differs from
 * where it rests. Its own scrolls always go TO the value, so they never read as a host's choice. A blank
 * wheel shows real values while the host drags it, and reports the drag's start to [onDragStart].
 */
@Composable
private fun SettlingWheel(
    wheel: WheelSpec,
    allowed: (Int) -> Boolean,
    onSettle: (Int) -> Unit,
    onDragStart: () -> Unit = {},
) {
    val reduceMotion = LocalReduceMotion.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = wheel.value)
    val center = rememberCenteredRow(listState, wheel.count)
    val current by rememberUpdatedState(wheel)
    val settle by rememberUpdatedState(onSettle)
    val dragStarted by rememberUpdatedState(onDragStart)
    var dragged by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun moveTo(index: Int) {
        if (reduceMotion) listState.scrollToItem(index) else listState.animateScrollToItem(index)
    }

    suspend fun cameToRest(index: Int) {
        dragged = false
        settle(index)
        withFrameNanos { }
        // The rules may have pulled the value elsewhere — or kept it where it was, which no key would notice.
        if (index != current.value) moveTo(current.value)
    }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) {
                dragged = true
                dragStarted()
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.filter { !it }.collect {
            val rest = centeredIndex(listState, wheel.count)
            when {
                rest != current.value || (dragged && current.blank) -> { moveTo(rest); cameToRest(rest) }
                dragged -> { dragged = false; moveTo(rest) }
            }
        }
    }
    LaunchedEffect(wheel.value) {
        if (!listState.isScrollInProgress && center != wheel.value) moveTo(wheel.value)
    }

    WheelList(listState, wheel, showBlank = wheel.blank && !dragged, center, allowed, reduceMotion) { index ->
        scope.launch { moveTo(index); cameToRest(index) }
    }
}

/** The row on the reading line right now, read directly (a collector must not wait for recomposition). */
private fun centeredIndex(listState: LazyListState, count: Int): Int {
    val info = listState.layoutInfo
    val row = info.visibleItemsInfo.firstOrNull()?.size?.takeIf { it > 0 } ?: return listState.firstVisibleItemIndex
    val settled = listState.firstVisibleItemScrollOffset.toFloat() / row
    return (listState.firstVisibleItemIndex + kotlin.math.round(settled).toInt()).coerceIn(0, count - 1)
}

@Composable
private fun WheelList(
    listState: LazyListState,
    wheel: WheelSpec,
    showBlank: Boolean,
    center: Int,
    allowed: (Int) -> Boolean,
    reduceMotion: Boolean,
    onTap: (Int) -> Unit,
) {
    val snapFling = rememberSnapFlingBehavior(lazyListState = listState)
    LazyColumn(
        state = listState,
        flingBehavior = if (reduceMotion) ScrollableDefaults.flingBehavior() else snapFling,
        contentPadding = PaddingValues(vertical = WheelRowHeight * ((WHEEL_VISIBLE_ROWS - 1) / 2)),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(44.dp).height(WheelRowHeight * WHEEL_VISIBLE_ROWS).semantics {
            contentDescription = wheel.description
            stateDescription = if (wheel.blank) BLANK_STATE else wheel.value.toString().padStart(2, '0')
        },
    ) {
        items(wheel.count) { i ->
            SettlingRow(
                text = if (showBlank && i == center) BLANK_LABEL else i.toString().padStart(2, '0'),
                distance = abs(i - center),
                blocked = !allowed(i),
                onClick = { onTap(i) },
            )
        }
    }
}

/**
 * One row: dimmed by its distance from the reading line, struck through — and disabled — when it cannot be
 * settled on. Tapping an enabled row brings it to the reading line and settles there.
 */
@Composable
private fun SettlingRow(text: String, distance: Int, blocked: Boolean, onClick: () -> Unit) {
    val alpha = when {
        blocked -> BLOCKED_ALPHA
        distance == 0 -> WHEEL_SELECTED_ALPHA
        distance == 1 -> WHEEL_NEIGHBOUR_ALPHA
        else -> WHEEL_DISTANT_ALPHA
    }
    val clickable = Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = !blocked,
        role = Role.Button,
        onClick = onClick,
    )
    Box(
        modifier = Modifier.height(WheelRowHeight).fillMaxWidth().then(clickable),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = if (distance == 0) {
                MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            } else {
                MaterialTheme.typography.bodyLarge
            },
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            textDecoration = if (blocked) TextDecoration.LineThrough else null,
            maxLines = 1,
        )
    }
}
