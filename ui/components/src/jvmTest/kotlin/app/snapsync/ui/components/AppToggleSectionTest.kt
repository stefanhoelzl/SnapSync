package app.snapsync.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

/**
 * The **switch-section** idiom of the join gate ([AppToggleSection]): the whole header row is ONE
 * `Role.Switch` node (the inner switch is drawing only), so assistive tech announces one on/off control per
 * section, and either the row or the switch flips it exactly once.
 */
class AppToggleSectionTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the header is a single switch node reflecting the checked state`() {
        var checked by mutableStateOf(true)
        rule.setContent {
            AppToggleSection(title = "Share my photos", checked = checked, onCheckedChange = { checked = it }) {
                AppSectionNote("a consequence line")
            }
        }
        // Exactly one switch — not two competing targets (the row + the drawn switch would double-fire).
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertCountEquals(1)
        rule.onNodeWithText("Share my photos")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        // The content slot renders beneath the header.
        rule.onNodeWithText("a consequence line").assertExists()
    }

    @Test
    fun `clicking the row toggles the section both ways`() {
        var checked by mutableStateOf(true)
        rule.setContent {
            AppToggleSection(title = "Receive everyone's photos", checked = checked, onCheckedChange = { checked = it }) {}
        }
        rule.onNodeWithText("Receive everyone's photos").performClick()
        assertEquals(false, checked)
        rule.onNodeWithText("Receive everyone's photos")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))

        rule.onNodeWithText("Receive everyone's photos").performClick()
        assertEquals(true, checked)
        rule.onNodeWithText("Receive everyone's photos")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    }
}
