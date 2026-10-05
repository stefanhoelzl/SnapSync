package app.snapsync.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
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
import app.snapsync.ui.components.resources.Res
import app.snapsync.ui.components.resources.range_end_pick_time
import app.snapsync.ui.components.resources.wheel_end_hour
import app.snapsync.ui.components.resources.wheel_end_minute
import app.snapsync.ui.components.resources.wheel_start_hour

/**
 * The inline [AppEventRangePicker] (capability `create-event`): the calendar's tap cycle, an end time that
 * stays blank until a Until wheel is touched, and a window that greys days past 30.
 *
 * March 2026 is used (`2026-03-09` is a Monday) so day descriptions are stable whatever the wall-clock
 * "today" is. Reduce motion is REQUIRED: animating wheels never let the scene idle.
 */
class AppEventRangePickerTest {

    private companion object {
        val TEN_DAYS = RangeBounds.lastingAtMost { from -> LocalDateTime(from.date.plus(10, DateTimeUnit.DAY), from.time) }

        /** The clock's hour as the screen hands it over: a blank end hour fills with it. */
        const val CLOCK_HOUR = 15
    }

    @get:Rule
    val rule = createComposeRule()

    private var range by mutableStateOf(EventRange(from = LocalDateTime(2026, 3, 10, 9, 0)))

    @Test
    fun `the last day starts on the start's day with its time blank`() {
        setPicker()
        rule.onNodeWithText(str(Res.string.range_end_pick_time, "10 Mar 2026")).assertExists()
        rule.onNodeWithContentDescription(str(Res.string.wheel_end_hour), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "not set"))
        rule.onNodeWithContentDescription(str(Res.string.wheel_start_hour), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "09"))
    }

    @Test
    fun `the first tap on a later day places the last day and leaves its time blank`() {
        setPicker()
        rule.onNodeWithContentDescription("Thursday, 12 March 2026").performClick()
        rule.waitForIdle()
        assertEquals(LocalDate(2026, 3, 12), range.endDay)
        assertNull(range.untilTime)
        rule.onNodeWithText(str(Res.string.range_end_pick_time, "12 Mar 2026")).assertExists()
    }

    @Test
    fun `tapping a Until hour row sets the hour but leaves the minute and the end unset`() {
        setPicker()
        rule.onNodeWithContentDescription("Thursday, 12 March 2026").performClick()
        // The blank wheel sits over the start's 09, so 10 is the row just below the reading line.
        rule.onNode(hasText("10") and hasAnyAncestor(hasContentDescription(str(Res.string.wheel_end_hour))), useUnmergedTree = true)
            .performClick()
        rule.waitForIdle()
        assertEquals(10, range.untilHour)
        assertNull(range.until)
        rule.onNodeWithText(str(Res.string.range_end_pick_time, "12 Mar 2026")).assertExists()
        rule.onNodeWithContentDescription(str(Res.string.wheel_end_minute), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "not set"))
    }

    @Test
    fun `dragging the blank Until minutes fills the clock's hour and shows minutes while moving`() {
        setPicker()
        rule.onNodeWithContentDescription("Thursday, 12 March 2026").performClick()
        val minutes = rule.onNodeWithContentDescription(str(Res.string.wheel_end_minute), useUnmergedTree = true)
        minutes.performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, -height / 2f))
        }
        rule.waitForIdle()
        assertEquals(CLOCK_HOUR, range.untilHour, "the hour fills the moment the minutes start moving")
        rule.onAllNodes(hasText("--"), useUnmergedTree = true).assertCountEquals(0)
        minutes.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(CLOCK_HOUR, range.until?.hour)
        assertEquals(LocalDate(2026, 3, 12), range.until?.date)
    }

    @Test
    fun `the unset end's summary is a button that asks for the end time`() {
        var asked = 0
        setPicker(EndTimeGuide(showRequests = 0, onPickEndTime = { asked++ }))
        rule.onNodeWithText(str(Res.string.range_end_pick_time, "10 Mar 2026")).performClick()
        assertEquals(1, asked)
    }

    @Test
    fun `a set end's summary is not a button`() {
        var asked = 0
        range = EventRange(LocalDateTime(2026, 3, 10, 9, 0), LocalDate(2026, 3, 12), kotlinx.datetime.LocalTime(10, 30), endPending = false)
        setPicker(EndTimeGuide(showRequests = 0, onPickEndTime = { asked++ }))
        rule.onNodeWithText("12 Mar 2026, 10:30").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        rule.onNodeWithText("12 Mar 2026, 10:30").performClick()
        assertEquals(0, asked)
    }

    @Test
    fun `while the last day is pending, days past the window are disabled`() {
        setPicker()
        rule.onNodeWithContentDescription("Friday, 20 March 2026").assertIsEnabled()     // exactly the limit
        rule.onNodeWithContentDescription("Saturday, 21 March 2026").assertIsNotEnabled() // one day past it
    }

    @Test
    fun `a same-day end before the start cannot be tapped`() {
        setPicker()
        rule.onNode(hasText("08") and hasAnyAncestor(hasContentDescription(str(Res.string.wheel_end_hour))), useUnmergedTree = true)
            .onParent().assertIsNotEnabled()
    }

    @Test
    fun `dragging the last day moves it`() {
        setPicker()
        rule.onNodeWithContentDescription("Thursday, 12 March 2026").performClick()
        rule.onNodeWithContentDescription("Thursday, 12 March 2026").performTouchInput {
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
        rule.onNodeWithContentDescription("Monday, 16 March 2026").performTouchInput {
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

    @Test
    fun `each day cell announces its full date and the ends report selected`() {
        setPicker()
        rule.onNodeWithContentDescription("Tuesday, 10 March 2026").assertIsSelected()
        rule.onNodeWithContentDescription("Monday, 9 March 2026").assertIsNotSelected()
    }

    private fun setPicker(endTime: EndTimeGuide = EndTimeGuide.NONE) {
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                AppEventRangePicker(
                    range = range,
                    bounds = TEN_DAYS,
                    note = "note",
                    currentHour = { CLOCK_HOUR },
                    endTime = endTime,
                    onChange = { range = it },
                )
            }
        }
    }
}
