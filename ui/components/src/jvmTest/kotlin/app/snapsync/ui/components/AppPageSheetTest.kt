package app.snapsync.ui.components

import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The page sheet ([AppPageSheet]) the event's settings open in (capability `manage-membership`): its content shows,
 * and the ways out — the scrim above it, and a swipe down — all end in the one dismissal.
 */
class AppPageSheetTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `a tap on the screen above the sheet dismisses it`() {
        var dismissed = 0
        rule.setContent {
            AppTheme(
                platformDates,
            ) { AppPageSheet(onDismiss = { dismissed++ }) { Text("Share my photos") } }
        }
        rule.onNodeWithText("Share my photos").assertExists()
        // The scrim spans the screen; its centre is under the sheet, so tap where it shows — the strip at the top.
        rule.onNodeWithContentDescription("Close sheet").performTouchInput { click(Offset(centerX, 5f)) }
        rule.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun `swiping the sheet down dismisses it`() {
        var dismissed = 0
        rule.setContent {
            AppTheme(
                platformDates,
            ) { AppPageSheet(onDismiss = { dismissed++ }) { Text("Share my photos") } }
        }
        rule.onNodeWithText("Share my photos").performTouchInput { swipeDown(startY = top, endY = bottom + 2000f) }
        rule.waitForIdle()
        assertEquals(1, dismissed)
    }
}
