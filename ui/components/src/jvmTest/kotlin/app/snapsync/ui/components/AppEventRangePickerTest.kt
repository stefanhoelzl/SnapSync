package app.snapsync.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import org.junit.Rule

/**
 * The inline [AppEventRangePicker] (capability `create-event`): the calendar's tap cycle, an end time that
 * stays blank until a Until wheel is touched, and a window that greys days past 30.
 *
 * March 2026 is used (`2026-03-09` is a Monday) so day descriptions are stable whatever the wall-clock
 * "today" is. Reduce motion is REQUIRED: animating wheels never let the scene idle.
 */
class AppEventRangePickerTest {

    private companion object {
        val TEN_DAYS = LatestUntil { from -> LocalDateTime(from.date.plus(10, DateTimeUnit.DAY), from.time) }
    }

    @get:Rule
    val rule = createComposeRule()

    private var range by mutableStateOf(EventRange(from = LocalDateTime(2026, 3, 10, 9, 0)))

    @Test
    fun `the last day starts on the start's day with its time blank`() {
        setPicker()
        rule.onNodeWithText("10 Mar 2026, pick a time").assertExists()
        rule.onNodeWithContentDescription("Until hour", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "not set"))
        rule.onNodeWithContentDescription("From hour", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "09"))
    }

    @Test
    fun `the first tap on a later day places the last day and leaves its time blank`() {
        setPicker()
        rule.onNodeWithContentDescription("Thursday 12 March 2026").performClick()
        rule.waitForIdle()
        assertEquals(LocalDate(2026, 3, 12), range.endDay)
        assertNull(range.untilTime)
        rule.onNodeWithText("12 Mar 2026, pick a time").assertExists()
    }

    @Test
    fun `tapping a Until hour row sets the end with the minutes at zero`() {
        setPicker()
        rule.onNodeWithContentDescription("Thursday 12 March 2026").performClick()
        // The blank wheel sits over the start's 09, so 10 is the row just below the reading line.
        rule.onNode(hasText("10") and hasAnyAncestor(hasContentDescription("Until hour")), useUnmergedTree = true)
            .performClick()
        rule.waitForIdle()
        assertEquals(LocalDateTime(2026, 3, 12, 10, 0), range.until)
        rule.onNodeWithText("12 Mar 2026, 10:00").assertExists()
    }

    @Test
    fun `while the last day is pending, days past the window are disabled`() {
        setPicker()
        rule.onNodeWithContentDescription("Friday 20 March 2026").assertIsEnabled()     // exactly the limit
        rule.onNodeWithContentDescription("Saturday 21 March 2026").assertIsNotEnabled() // one day past it
    }

    @Test
    fun `a same-day end before the start cannot be tapped`() {
        setPicker()
        rule.onNode(hasText("08") and hasAnyAncestor(hasContentDescription("Until hour")), useUnmergedTree = true)
            .onParent().assertIsNotEnabled()
    }

    @Test
    fun `dragging the last day moves it`() {
        setPicker()
        rule.onNodeWithContentDescription("Thursday 12 March 2026").performClick()
        rule.onNodeWithContentDescription("Thursday 12 March 2026").performTouchInput {
            val to = centerRight + androidx.compose.ui.geometry.Offset(width * 2f, 0f) // two columns on: Saturday
            swipe(center, to, durationMillis = 300)
        }
        rule.waitForIdle()
        assertEquals(LocalDate(2026, 3, 10), range.from.date)
        assertEquals(LocalDate(2026, 3, 14), range.endDay)
    }

    @Test
    fun `a long press then a sweep selects a new range`() {
        setPicker()
        rule.onNodeWithContentDescription("Monday 16 March 2026").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(androidx.compose.ui.geometry.Offset(width * 2f, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(LocalDate(2026, 3, 16), range.from.date)
        assertEquals(LocalDate(2026, 3, 18), range.endDay)
        assertNull(range.untilTime)
    }

    private fun setPicker() {
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                AppEventRangePicker(range = range, latest = TEN_DAYS, note = "note", onChange = { range = it })
            }
        }
    }
}
