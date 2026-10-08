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
import app.snapsync.ui.components.resources.wheel_start_hour
import kotlinx.datetime.LocalDateTime
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The range picker as the join and settings surfaces open it ([RangePickerDialog]): the create screen's
 * [RangeEditor] in a dialog, held to the event's window, opened on the chosen range complete, with preset
 * chips and Cancel / OK (capabilities `join-event`, `manage-membership`).
 *
 * The window is 10–20 March 2026 (`2026-03-09` is a Monday), so day descriptions are stable whatever the
 * wall-clock "today" is. Reduce motion is required: animating wheels never let the scene idle.
 */
class RangePickerDialogTest {

    private companion object {
        val START = LocalDateTime(2026, 3, 10, 18, 0)
        val END = LocalDateTime(2026, 3, 20, 18, 0)
        val WINDOW = RangeBounds.within(START, END)
        val WHOLE = EventRange(START, END.date, END.time, endPending = false)
    }

    @get:Rule
    val rule = createComposeRule()

    private var confirmed: Pair<LocalDateTime, LocalDateTime>? = null
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
    fun `OK unchanged reports the range it opened on`() {
        setDialog()
        rule.onNodeWithText(str(Res.string.picker_save)).performClick()
        assertEquals(START to END, confirmed)
    }

    @Test
    fun `a first tap starts a new range and OK waits for its last day`() {
        setDialog()
        rule.onNodeWithContentDescription("Friday, 13 March 2026").performClick()
        rule.onNodeWithText(str(Res.string.picker_save)).assertIsNotEnabled()
        rule.onNodeWithContentDescription("Sunday, 15 March 2026").performClick()
        rule.onNodeWithText(str(Res.string.picker_save)).assertIsEnabled().performClick()
        assertEquals(LocalDateTime(2026, 3, 13, 18, 0) to LocalDateTime(2026, 3, 15, 18, 0), confirmed)
    }

    @Test
    fun `dragging the end narrows the range`() {
        setDialog()
        rule.onNodeWithContentDescription("Friday, 20 March 2026").performTouchInput {
            swipe(center, center - Offset(width * 3f, 0f), durationMillis = 300) // three columns back: Tuesday 17
        }
        rule.waitForIdle()
        rule.onNodeWithText(str(Res.string.picker_save)).performClick()
        assertEquals(START to LocalDateTime(2026, 3, 17, 18, 0), confirmed)
    }

    @Test
    fun `a preset chip reports its choice and never the calendar's span`() {
        setDialog()
        rule.onNodeWithText("Whole event").performClick()
        assertEquals("whole", chosen)
        assertNull(confirmed)
    }

    private fun setDialog(initial: EventRange = WHOLE) {
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true, LocalDateFormats provides dateFormats(null)) {
                RangePickerDialog(
                    initial = initial,
                    bounds = WINDOW,
                    title = "Which photos to share",
                    presets = listOf(RangePresetChip("Whole event", selected = true) { chosen = "whole" }),
                    onDismiss = {},
                    onConfirm = { f, u -> confirmed = f to u },
                )
            }
        }
    }
}
