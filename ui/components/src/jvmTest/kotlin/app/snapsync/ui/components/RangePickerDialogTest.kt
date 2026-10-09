package app.snapsync.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import app.snapsync.ui.components.resources.Res
import app.snapsync.ui.components.resources.picker_save
import app.snapsync.ui.components.resources.share_range_title
import app.snapsync.ui.components.resources.wheel_end_hour
import app.snapsync.ui.components.resources.wheel_not_set
import app.snapsync.ui.components.resources.wheel_start_hour
import app.snapsync.ui.state.PickerView
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The range picker as the join and settings surfaces open it ([RangePickerDialog]): the create screen's
 * [RangeEditor] in a dialog with preset chips and Cancel / OK (capabilities `join-event`, `manage-membership`). It draws
 * the picker it is given — the app opens it on the chosen range, held to the event's window — offers OK only while
 * that picker is complete, and reports every gesture.
 *
 * The window is 10–20 March 2026 (`2026-03-09` is a Monday), so day descriptions are stable whatever the
 * wall-clock "today" is. Reduce motion is required: animating wheels never let the scene idle.
 */
class RangePickerDialogTest {

    private companion object {
        val START = LocalDateTime(2026, 3, 10, 18, 0)

        /** The whole event, complete, held to its window. */
        val WHOLE = picker(
            START,
            endDay = LocalDate(2026, 3, 20),
            untilHour = 18,
            untilMinute = 0,
            endPending = false,
            firstDay = LocalDate(2026, 3, 10),
            lastDay = LocalDate(2026, 3, 20),
            complete = true,
        )
    }

    @get:Rule
    val rule = createComposeRule()

    private val log = GestureLog()
    private var confirmed = 0
    private var chosen: String? = null

    @Test
    fun `the title is a heading and both ends' wheels show the chosen range`() {
        setDialog()
        rule.onNodeWithText(
            str(Res.string.share_range_title),
        ).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNodeWithContentDescription(str(Res.string.wheel_start_hour), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "18"))
        rule.onNodeWithContentDescription(str(Res.string.wheel_end_hour), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "18"))
    }

    @Test
    fun `days outside the event are disabled`() {
        setDialog()
        rule.onNodeWithContentDescription("Monday, 9 March 2026").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Tuesday, 10 March 2026").assertIsEnabled()
        rule.onNodeWithContentDescription("Friday, 20 March 2026").assertIsEnabled()
        rule.onNodeWithContentDescription("Saturday, 21 March 2026").assertIsNotEnabled()
    }

    @Test
    fun `OK on a complete range takes it`() {
        setDialog()
        rule.onNodeWithText(str(Res.string.picker_save)).performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun `OK waits while the range is not complete`() {
        setDialog(WHOLE.copy(endDay = LocalDate(2026, 3, 13), endPending = true, complete = false))
        rule.onNodeWithText(str(Res.string.picker_save)).assertIsNotEnabled()
    }

    @Test
    fun `a tap on a day reports it`() {
        setDialog()
        rule.onNodeWithContentDescription("Friday, 13 March 2026").performClick()
        rule.waitForIdle()
        assertEquals(listOf("day 2026-03-13"), log.events)
    }

    @Test
    fun `dragging the end reports the day it reaches`() {
        setDialog()
        rule.onNodeWithContentDescription("Friday, 20 March 2026").performTouchInput {
            swipe(center, center - Offset(width * 3f, 0f), durationMillis = 300) // three columns back: Tuesday 17
        }
        rule.waitForIdle()
        assertEquals("end 2026-03-17", log.last("end"))
    }

    @Test
    fun `a preset chip reports its choice and never the calendar's span`() {
        setDialog()
        rule.onNodeWithText("Whole event").performClick()
        assertEquals("whole", chosen)
        assertEquals(0, confirmed)
    }

    @Test
    fun `an end time the range has no room for is shown not set`() {
        setDialog(
            WHOLE.copy(
                endDay = LocalDate(2026, 3, 20),
                untilHour = null,
                untilMinute = null,
                untilTimes = null,
                complete = false,
            ),
        )
        rule.onNodeWithContentDescription(str(Res.string.wheel_end_hour), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, str(Res.string.wheel_not_set)))
        rule.onNodeWithText(str(Res.string.picker_save)).assertIsNotEnabled()
    }

    private fun setDialog(picker: PickerView = WHOLE) {
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true, LocalDateFormats provides dateFormats(null)) {
                RangePickerDialog(
                    picker = picker,
                    title = "Which photos to share",
                    presets = listOf(RangePresetChip("Whole event", selected = true) { chosen = "whole" }),
                    gestures = log.gestures,
                    onDismiss = {},
                    onConfirm = { confirmed++ },
                )
            }
        }
    }
}
