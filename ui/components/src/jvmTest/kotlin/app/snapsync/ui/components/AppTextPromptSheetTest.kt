@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.snapsync.control.Verifies
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The text prompt sheet's dismissal (capabilities `manage-membership`, `privacy-security`): while idle, Cancel and a
 * swipe down close it; while a request is in flight, every dismissal route is refused alike.
 */
class AppTextPromptSheetTest {

    private companion object {
        const val TITLE = "Rename event"
        const val CANCEL = "Cancel"
    }

    @get:Rule
    val rule = createComposeRule()

    private var dismissed = 0

    @Test
    fun `while idle Cancel closes the sheet`() {
        setSheet(busy = false)
        rule.onNodeWithText(CANCEL).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `while idle a swipe down closes the sheet`() {
        setSheet(busy = false)
        rule.onNodeWithText(TITLE).performTouchInput { swipeDown(startY = centerY, endY = centerY + 1500f) }
        rule.waitForIdle()
        assertEquals(1, dismissed)
    }

    // "Text entry sheets stay usable while typing": these prove the confirm and dismissal rules. Whether the keyboard
    // covers the actions is the device's layout, which no JVM scene has a keyboard to show.
    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "Text entry sheets stay usable while typing",
        scenario = "A running sheet cannot be dismissed",
    )
    fun `while busy neither Cancel nor a swipe down closes the sheet or moves it off the screen`() {
        setSheet(busy = true)
        rule.onNodeWithText(CANCEL).performClick()
        rule.onNodeWithText(TITLE).performTouchInput { swipeDown(startY = centerY, endY = centerY + 1500f) }
        rule.waitForIdle()
        assertEquals(0, dismissed)
        rule.onNodeWithText(TITLE).assertIsDisplayed()
    }

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "Text entry sheets stay usable while typing",
        scenario = "A running sheet cannot be dismissed",
    )
    fun `while busy a tap outside the sheet leaves it open and on the screen`() {
        setSheet(busy = true)
        tapOutside()
        assertEquals(0, dismissed)
        rule.onNodeWithText(TITLE).assertIsDisplayed()
    }

    @Test
    fun `while idle a tap outside the sheet closes it`() {
        setSheet(busy = false)
        tapOutside()
        assertEquals(1, dismissed)
    }

    /**
     * A tap on the scrim beside the sheet, which is at most 640 dp wide and centred in a wider window. The sheet is a
     * popup, so its scrim is the last of the two roots.
     */
    private fun tapOutside() {
        rule.onAllNodes(isRoot()).onLast().performTouchInput { click(Offset(8f, centerY)) }
        rule.waitForIdle()
    }

    @Test
    fun `while busy Save is unavailable even with a text worth sending`() {
        setSheet(busy = true, submitUnchanged = true)
        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "Text entry sheets stay usable while typing",
        scenario = "Unchanged text cannot be submitted",
    )
    fun `a text left as it opened cannot be saved`() {
        setSheet(busy = false)
        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "Text entry sheets stay usable while typing",
        scenario = "A report the app wrote can be sent as it stands",
    )
    fun `while idle a text worth sending can be saved`() {
        var saved: String? = null
        setSheet(busy = false, submitUnchanged = true, onConfirm = { saved = it })
        rule.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals("Party", saved)
    }

    // Our part of "The keyboard does not cover confirm": in what the keyboard leaves of a phone's height, both actions
    // stay on the screen and work. That the keyboard reports its height as an inset is the operating system's.
    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "Text entry sheets stay usable while typing",
        scenario = "The keyboard does not cover confirm",
    )
    fun `in the height a keyboard leaves both actions stay on screen and work while typing`() =
        runDesktopComposeUiTest(width = 375, height = 340) {
            var saved: String? = null
            setContent { Sheet(busy = false, onConfirm = { saved = it }) }
            onNode(hasSetTextAction()).performTextReplacement("Party 2")
            onNodeWithText(CANCEL).assertIsDisplayed()
            onNodeWithText("Save").assertIsDisplayed().assertIsEnabled().performClick()
            assertEquals("Party 2", saved)
            onNodeWithText(CANCEL).performClick()
            assertEquals(1, dismissed)
        }

    private fun setSheet(busy: Boolean, submitUnchanged: Boolean = false, onConfirm: (String) -> Unit = {}) {
        rule.setContent { Sheet(busy, submitUnchanged, onConfirm) }
    }

    @Composable
    private fun Sheet(busy: Boolean, submitUnchanged: Boolean = false, onConfirm: (String) -> Unit = {}) {
        run {
            AppTheme(platformDates) {
                AppTextPromptSheet(
                    copy = DialogCopy(
                        TITLE,
                        confirmLabel = "Save",
                        cancelLabel = CANCEL,
                        body = "Everyone sees the new name.",
                    ),
                    field = PromptField(
                        placeholder = "Event name",
                        initialValue = "Party",
                        busy = busy,
                        submitUnchanged = submitUnchanged,
                    ),
                    onConfirm = onConfirm,
                    onDismiss = { dismissed++ },
                )
            }
        }
    }
}
