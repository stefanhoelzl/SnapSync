package app.snapsync.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import kotlinx.datetime.LocalTime
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The settling wheels (capabilities `create-event`, `join-event`): a wheel reports a value only when the host brings
 * it to rest on one, follows wherever the host's rules then put the value, and is never moved under a finger.
 *
 * The rules here are a stand-in for a range's: no time before 10:00, and an hour settled earlier becomes 10.
 */
class SettlingWheelsTest {

    private companion object {
        const val HOUR = "Start hour"
        const val MINUTE = "Start minute"
        val FROM_TEN: (LocalTime) -> Boolean = { it >= LocalTime(10, 0) }
    }

    @get:Rule
    val rule = createComposeRule()

    private var hour by mutableIntStateOf(12)
    private var blank by mutableStateOf(false)
    private val settled = mutableListOf<Int>()

    @Test
    fun `a wheel brought to rest on a row the rules refuse follows them to the value they chose`() {
        setWheels()
        drag(HOUR) { rows(4) } // from 12 down to 08
        assertEquals(listOf(8), settled)
        assertEquals(10, hour)
        rule.onNodeWithContentDescription(HOUR, useUnmergedTree = true).assert(reading("10"))
    }

    @Test
    fun `a set wheel nudged less than half a row springs back without settling`() {
        setWheels()
        drag(HOUR) { rows(0.3f) }
        assertEquals(emptyList(), settled)
        assertEquals(12, hour)
    }

    @Test
    fun `a blank wheel dragged away and back to its anchor settles on it`() {
        blank = true
        setWheels()
        drag(HOUR) {
            rows(-1)
            rows(1)
        }
        assertEquals(listOf(9), settled, "landing back on the anchor still means this one")
        assertEquals(10, hour)
    }

    @Test
    fun `with motion allowed a tapped row scrolls to the reading line and settles`() {
        setWheels(reduceMotion = false)
        rule.onNode(hasText("13") and hasAnyAncestor(hasContentDescription(HOUR)), useUnmergedTree = true)
            .performClick()
        rule.waitForIdle()
        // Animated, the scroll's own end reports the row as well as the tap: every rule a settle feeds is idempotent.
        assertEquals(setOf(13), settled.toSet())
        rule.onNodeWithContentDescription(HOUR, useUnmergedTree = true).assert(reading("13"))
    }

    @Test
    fun `a value that changes while the host holds the wheel does not move it from under the finger`() {
        setWheels()
        rule.onNodeWithContentDescription(HOUR, useUnmergedTree = true).performTouchInput {
            down(center)
            rows(-2) // up to 14, still held
        }
        rule.waitForIdle()
        hour = 20
        rule.waitForIdle()
        rule.onNodeWithContentDescription(HOUR, useUnmergedTree = true).performTouchInput {
            advanceEventTime(500)
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf(14), settled, "the host's own drag decides where the wheel comes to rest")
    }

    /** A drag on the wheel [description] names, moved by [moves] and released without a fling. */
    private fun drag(description: String, moves: TouchInjectionScope.() -> Unit) {
        rule.onNodeWithContentDescription(description, useUnmergedTree = true).performTouchInput {
            down(center)
            moves()
            advanceEventTime(500)
            up()
        }
        rule.waitForIdle()
    }

    /** The finger moved down by [count] rows (up when negative): the wheel turns to earlier values. */
    private fun TouchInjectionScope.rows(count: Number) {
        val row = height / WHEEL_VISIBLE_ROWS.toFloat()
        // Two steps, so the first crosses the touch slop and the second moves the list by the whole distance.
        moveBy(Offset(0f, viewConfiguration.touchSlop + 1f) * kotlin.math.sign(count.toFloat()))
        moveBy(Offset(0f, row * count.toFloat()))
    }

    private fun reading(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private fun setWheels(reduceMotion: Boolean = true) {
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                Row {
                    SettlingTimeWheels(
                        caption = WheelCaption("Starts", hour = HOUR, minute = MINUTE),
                        hour = hour.takeUnless { blank },
                        minute = 0,
                        anchor = LocalTime(9, 0),
                        allowed = FROM_TEN,
                        onHour = {
                            settled += it
                            hour = maxOf(it, 10)
                            blank = false
                        },
                        onMinute = {},
                    )
                }
            }
        }
    }
}
