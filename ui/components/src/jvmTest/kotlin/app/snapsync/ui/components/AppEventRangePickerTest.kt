package app.snapsync.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import app.snapsync.model.PickerView
import app.snapsync.model.TimeSpan
import app.snapsync.ui.components.resources.Res
import app.snapsync.ui.components.resources.range_end_pick_time
import app.snapsync.ui.components.resources.wheel_end_hour
import app.snapsync.ui.components.resources.wheel_end_minute
import app.snapsync.ui.components.resources.wheel_start_hour
import app.snapsync.ui.components.resources.wheel_start_minute
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The inline [AppEventRangePicker] (capability `create-event`): it draws the range it is given — a blank end time stays
 * blank, days past the last pickable one are greyed, times outside the spans are struck — and reports each tap, drag
 * and wheel settle as the gesture it was. What a gesture makes is the app's, tested with the rules.
 *
 * March 2026 is used (`2026-03-09` is a Monday) so day descriptions are stable whatever the wall-clock
 * "today" is. Reduce motion is REQUIRED: animating wheels never let the scene idle.
 */
class AppEventRangePickerTest {

    private companion object {
        val START = LocalDateTime(2026, 3, 10, 9, 0)

        /** A fresh range on [START]: its last day pending, ten days at most, an end only after the start. */
        val FRESH = picker(
            START,
            lastDay = LocalDate(2026, 3, 20),
            untilTimes = TimeSpan(LocalTime(9, 1), LocalTime(23, 59)),
        )
    }

    @get:Rule
    val rule = createComposeRule()

    private val log = GestureLog()

    @Test
    fun `the last day starts on the start's day with its time blank`() {
        setPicker(FRESH)
        rule.onNodeWithText(str(Res.string.range_end_pick_time, "10 Mar 2026")).assertExists()
        rule.onNodeWithContentDescription(str(Res.string.wheel_end_hour), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "not set"))
        rule.onNodeWithContentDescription(str(Res.string.wheel_start_hour), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "09"))
    }

    @Test
    fun `a tap on a day reports that day`() {
        setPicker(FRESH)
        rule.onNodeWithContentDescription("Thursday, 12 March 2026").performClick()
        rule.waitForIdle()
        assertEquals(listOf("day 2026-03-12"), log.events)
    }

    @Test
    fun `a placed last day with a blank end time asks for the time on that day`() {
        setPicker(FRESH.copy(endDay = LocalDate(2026, 3, 12), endPending = false))
        rule.onNodeWithText(str(Res.string.range_end_pick_time, "12 Mar 2026")).assertExists()
    }

    @Test
    fun `tapping a Until hour row reports the hour`() {
        setPicker(FRESH.copy(endDay = LocalDate(2026, 3, 12), endPending = false))
        // The blank wheel sits over the start's 09, so 10 is the row just below the reading line.
        rule.onNode(
            hasText("10") and hasAnyAncestor(hasContentDescription(str(Res.string.wheel_end_hour))),
            useUnmergedTree = true,
        )
            .performClick()
        rule.waitForIdle()
        assertEquals("untilHour 10", log.last("untilHour"))
    }

    @Test
    fun `a set end hour with a blank minute shows the minute unset`() {
        setPicker(FRESH.copy(endDay = LocalDate(2026, 3, 12), endPending = false, untilHour = 10))
        rule.onNodeWithText(str(Res.string.range_end_pick_time, "12 Mar 2026")).assertExists()
        rule.onNodeWithContentDescription(str(Res.string.wheel_end_minute), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "not set"))
    }

    @Test
    fun `dragging the blank Until minutes reports it before the settle`() {
        setPicker(FRESH.copy(endDay = LocalDate(2026, 3, 12), endPending = false))
        val minutes = rule.onNodeWithContentDescription(str(Res.string.wheel_end_minute), useUnmergedTree = true)
        minutes.performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, -height / 2f))
        }
        rule.waitForIdle()
        assertEquals("untilMinuteDrag", log.events.first(), "the hour is asked for the moment the minutes start moving")
        minutes.performTouchInput { up() }
        rule.waitForIdle()
        assertTrue(log.last("untilMinute") != null, "the settle is reported: ${log.events}")
    }

    @Test
    fun `dragging the Until minutes under a chosen hour does not ask for one`() {
        setPicker(FRESH.copy(endDay = LocalDate(2026, 3, 12), endPending = false, untilHour = 11))
        val minutes = rule.onNodeWithContentDescription(str(Res.string.wheel_end_minute), useUnmergedTree = true)
        minutes.performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, -height / 2f))
            up()
        }
        rule.waitForIdle()
        assertTrue("untilMinuteDrag" !in log.events, "an hour already chosen is kept: ${log.events}")
    }

    @Test
    fun `the unset end's summary is a button that asks for the end time`() {
        var asked = 0
        setPicker(FRESH, EndTimeGuide(showRequests = 0, onPickEndTime = { asked++ }))
        rule.onNodeWithText(str(Res.string.range_end_pick_time, "10 Mar 2026")).performClick()
        assertEquals(1, asked)
    }

    @Test
    fun `a set end's summary is not a button`() {
        var asked = 0
        val set = FRESH.copy(endDay = LocalDate(2026, 3, 12), untilHour = 10, untilMinute = 30, endPending = false)
        setPicker(set, EndTimeGuide(showRequests = 0, onPickEndTime = { asked++ }))
        rule.onNodeWithText("12 Mar 2026, 10:30").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        rule.onNodeWithText("12 Mar 2026, 10:30").performClick()
        assertEquals(0, asked)
    }

    @Test
    fun `days past the last pickable one are disabled`() {
        setPicker(FRESH)
        rule.onNodeWithContentDescription("Friday, 20 March 2026").assertIsEnabled() // exactly the limit
        rule.onNodeWithContentDescription("Saturday, 21 March 2026").assertIsNotEnabled() // one day past it
    }

    @Test
    fun `an end time outside the span cannot be tapped`() {
        setPicker(FRESH)
        rule.onNode(
            hasText("08") and hasAnyAncestor(hasContentDescription(str(Res.string.wheel_end_hour))),
            useUnmergedTree = true,
        )
            .onParent().assertIsNotEnabled()
    }

    @Test
    fun `a start time no span allows cannot be tapped`() {
        setPicker(FRESH.copy(fromTimes = null))
        rule.onNode(
            hasText("10") and hasAnyAncestor(hasContentDescription(str(Res.string.wheel_start_hour))),
            useUnmergedTree = true,
        )
            .onParent().assertIsNotEnabled()
    }

    @Test
    fun `dragging the last day reports it day by day`() {
        setPicker(FRESH.copy(endDay = LocalDate(2026, 3, 12), endPending = false))
        rule.onNodeWithContentDescription("Thursday, 12 March 2026").performTouchInput {
            val to = centerRight + androidx.compose.ui.geometry.Offset(width * 2f, 0f) // two columns on: Saturday
            swipe(center, to, durationMillis = 300)
        }
        rule.waitForIdle()
        assertEquals("end 2026-03-14", log.last("end"))
        assertEquals(emptyList(), log.all("start"))
    }

    @Test
    fun `a long press then a sweep reports the sweep`() {
        setPicker(FRESH)
        rule.onNodeWithContentDescription("Monday, 16 March 2026").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(androidx.compose.ui.geometry.Offset(width * 2f, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals("sweep 2026-03-16 2026-03-18", log.last("sweep"))
    }

    @Test
    fun `each day cell announces its full date and the ends report selected`() {
        setPicker(FRESH)
        rule.onNodeWithContentDescription("Tuesday, 10 March 2026").assertIsSelected()
        rule.onNodeWithContentDescription("Monday, 9 March 2026").assertIsNotSelected()
    }

    @Test
    fun `tapping a From row reports the start's hour or minute`() {
        setPicker(FRESH)
        rule.onNode(
            hasText("10") and hasAnyAncestor(hasContentDescription(str(Res.string.wheel_start_hour))),
            useUnmergedTree = true,
        )
            .performClick()
        rule.waitForIdle()
        assertEquals("fromHour 10", log.last("fromHour"))
        rule.onNode(
            hasText("01") and hasAnyAncestor(hasContentDescription(str(Res.string.wheel_start_minute))),
            useUnmergedTree = true,
        )
            .performClick()
        rule.waitForIdle()
        assertEquals("fromMinute 1", log.last("fromMinute"))
    }

    private fun setPicker(picker: PickerView, endTime: EndTimeGuide = EndTimeGuide.NONE) {
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true, LocalDateFormats provides dateFormats(null)) {
                AppEventRangePicker(picker = picker, note = "note", gestures = log.gestures, endTime = endTime)
            }
        }
    }
}
