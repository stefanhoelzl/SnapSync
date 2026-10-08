package app.snapsync.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Until wheels' outline (capability `create-event`): each new request from the host outlines the wheels where
 * the missing end time is set, then lets the outline go — rising, holding and fading, or under reduce motion simply
 * showing and hiding. A request of 0 is none.
 */
@OptIn(ExperimentalTestApi::class)
class AppEventRangeHighlightTest {

    private companion object {
        val TEN_DAYS = RangeBounds.lastingAtMost { from ->
            LocalDateTime(from.date.plus(10, DateTimeUnit.DAY), from.time)
        }

        /** Past the 200 ms rise, inside the 700 ms hold: the outline is fully drawn. */
        const val AT_PEAK_MS = 400L

        /** Past the rise, the hold and the 700 ms fade together. */
        const val PAST_FADE_MS = 2_000L
    }

    private var requests by mutableIntStateOf(0)
    private var primary = Color.Unspecified

    @Test
    fun `a request outlines the Until wheels, then the outline fades away`() = runComposeUiTest {
        val before = settledPicker(reduceMotion = false)

        requests = 1
        mainClock.advanceTimeBy(AT_PEAK_MS)
        val peak = frame()
        assertTrue(peak.count(primary) > before.count(primary), "the outline is drawn in the accent colour")

        mainClock.advanceTimeBy(PAST_FADE_MS)
        assertEquals(before.count(primary), frame().count(primary), "the outline is gone once it has faded")
    }

    @Test
    fun `under reduce motion the outline shows and hides without animating`() = runComposeUiTest {
        val before = settledPicker(reduceMotion = true)

        requests = 1
        mainClock.advanceTimeBy(AT_PEAK_MS)
        val shown = frame()
        assertTrue(shown.count(primary) > before.count(primary), "the outline is drawn in the accent colour")
        mainClock.advanceTimeBy(AT_PEAK_MS)
        assertEquals(shown.count(primary), frame().count(primary), "the outline holds still: nothing rises or fades")

        mainClock.advanceTimeBy(PAST_FADE_MS)
        assertEquals(before.count(primary), frame().count(primary), "the outline is gone after its hold")
    }

    @Test
    fun `no request draws no outline`() = runComposeUiTest {
        val before = settledPicker(reduceMotion = false)
        mainClock.advanceTimeBy(AT_PEAK_MS)
        assertEquals(before.count(primary), frame().count(primary))
    }

    /** The picker with no request yet, its wheels settled: the frame every outline is measured against. */
    private fun ComposeUiTest.settledPicker(reduceMotion: Boolean): PixelMap {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                primary = MaterialTheme.colorScheme.primary
                AppEventRangePicker(
                    range = EventRange(from = LocalDateTime(2026, 3, 10, 9, 0)),
                    bounds = TEN_DAYS,
                    note = "note",
                    currentHour = { 15 },
                    endTime = EndTimeGuide(showRequests = requests, onPickEndTime = null),
                    onChange = {},
                )
            }
        }
        mainClock.advanceTimeBy(PAST_FADE_MS)
        return frame()
    }

    private fun ComposeUiTest.frame(): PixelMap = onRoot().captureToImage().toPixelMap()

    private fun PixelMap.count(color: Color): Int =
        (0 until width).sumOf { x -> (0 until height).count { y -> this[x, y] == color } }
}
