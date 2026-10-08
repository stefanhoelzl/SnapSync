package app.snapsync.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Today on the range calendar (capabilities `create-event`, `join-event`): the day the clock says is announced as
 * today, and marked with a ring unless the range already marks it — as an end, or inside the band.
 *
 * The range is 10–12 March 2026, placed.
 */
class CalendarTodayTest {

    private companion object {
        val TEN_DAYS = RangeBounds.lastingAtMost { from ->
            LocalDateTime(from.date.plus(10, DateTimeUnit.DAY), from.time)
        }
        val RANGE = EventRange(LocalDateTime(2026, 3, 10, 9, 0), endDay = LocalDate(2026, 3, 12), endPending = false)
    }

    @get:Rule
    val rule = createComposeRule()

    private var primary = Color.Unspecified

    @Test
    fun `today outside the range is announced as today and ringed`() {
        setCalendar(today = LocalDate(2026, 3, 16))
        val today = rule.onNodeWithContentDescription("Monday, 16 March 2026, today")
        assertTrue(today.ringed(), "an unselected today wears the ring")
        val otherDay = rule.onNodeWithContentDescription("Tuesday, 17 March 2026")
        assertTrue(!otherDay.ringed(), "a plain day wears none")
    }

    @Test
    fun `today as an end of the range is announced as today, the end's circle its only mark`() {
        setCalendar(today = LocalDate(2026, 3, 12))
        rule.onNodeWithContentDescription("Thursday, 12 March 2026, today").assertExists()
    }

    @Test
    fun `today inside the range is announced as today, the band its only mark`() {
        setCalendar(today = LocalDate(2026, 3, 11))
        val today = rule.onNodeWithContentDescription("Wednesday, 11 March 2026, today")
        assertTrue(!today.ringed(), "the band already marks it")
    }

    /** Whether the day cell draws any pixel in the accent colour — the ring, on a cell with no fill or band. */
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.ringed(): Boolean {
        val pixels = captureToImage().toPixelMap()
        return (0 until pixels.width).any { x -> (0 until pixels.height).any { y -> pixels[x, y] == primary } }
    }

    private fun setCalendar(today: LocalDate) {
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true, LocalDateFormats provides dateFormats(null)) {
                primary = MaterialTheme.colorScheme.primary
                AppEventRangePicker(
                    range = RANGE,
                    bounds = TEN_DAYS,
                    note = "note",
                    today = today,
                    currentHour = { 15 },
                    onChange = {},
                )
            }
        }
    }
}
