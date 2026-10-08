package app.snapsync.ui.components

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
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

    @Test
    fun `while busy neither Cancel nor a swipe down closes the sheet`() {
        setSheet(busy = true)
        rule.onNodeWithText(CANCEL).performClick()
        rule.onNodeWithText(TITLE).performTouchInput { swipeDown(startY = centerY, endY = centerY + 1500f) }
        rule.waitForIdle()
        assertEquals(0, dismissed)
    }

    @Test
    fun `while busy Save is unavailable even with a text worth sending`() {
        setSheet(busy = true, submitUnchanged = true)
        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun `while idle a text worth sending can be saved`() {
        var saved: String? = null
        setSheet(busy = false, submitUnchanged = true, onConfirm = { saved = it })
        rule.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals("Party", saved)
    }

    private fun setSheet(busy: Boolean, submitUnchanged: Boolean = false, onConfirm: (String) -> Unit = {}) {
        rule.setContent {
            AppTheme {
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
