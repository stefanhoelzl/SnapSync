package app.snapsync.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The screen heading: a long name stops at two lines with an ellipsis and keeps its edit control on screen,
 * and the details slot renders beneath it.
 */
private val PHONE_WIDTH = 375.dp

class ScreenHeadingTest {

    @get:Rule
    val rule = createComposeRule()

    private val longName = "Anna & Ben's Wedding Weekend at Lake Garda with the Whole Family and Friends 2026 Edition"

    @Test
    fun `a long name is cut after two lines and the edit control stays`() {
        rule.setContent {
            // A phone's width, so the name has to wrap as it would on the device.
            AppTheme(platformDates) {
                Box(Modifier.width(PHONE_WIDTH)) {
                    ScreenLayout(
                        title = "SnapSync",
                        heading = ScreenHeading(longName, onEdit = {}, editDescription = "Rename event") {
                            Text("You've joined this event")
                        },
                        bottomActions = null,
                        contentPinsActionCluster = false,
                        onTitleDoubleTap = null,
                        onMenu = null,
                    ) {}
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(longName).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        assertEquals(2, layout.lineCount)
        // Cut, not wrapped further. (Skiko reports no per-line ellipsis flag; the overflow is what it measures.)
        assertTrue(layout.hasVisualOverflow, "the rest of the name is cut off")
        rule.onNodeWithContentDescription("Rename event").assertExists()
        rule.onNodeWithText("You've joined this event").assertExists()
    }
}
