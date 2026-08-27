package app.snapsync.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue


// The time-of-day wheels: three snapping columns behind a selection band, sharing one row height and
// one visible-row count so the band lines up with whatever the columns render.

/** The wheel row geometry: three visible rows keeps the dialog compact under the calendar. */
private val WheelRowHeight = 38.dp
private const val WHEEL_VISIBLE_ROWS = 3

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
internal fun TimeWheels(
    hour: Int,
    minute: Int,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit,
    // Overridable so the range dialog can label its two wheel pairs distinctly ("From hour"/"Until hour")
    // rather than presenting two identically-described "Hour" columns.
    hourDescription: String = "Hour",
    minuteDescription: String = "Minute",
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background)
            .height(WheelRowHeight * WHEEL_VISIBLE_ROWS),
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
                    contentDescription = hourDescription
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
                    contentDescription = minuteDescription
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
    val padRows = (WHEEL_VISIBLE_ROWS - 1) / 2

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
        modifier = modifier.height(WheelRowHeight * WHEEL_VISIBLE_ROWS),
    ) {
        items(count) { i ->
            val distance = abs(i - centerIndex)
            val alpha = when (distance) {
                0 -> WHEEL_SELECTED_ALPHA
                1 -> WHEEL_NEIGHBOUR_ALPHA
                else -> WHEEL_DISTANT_ALPHA
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
