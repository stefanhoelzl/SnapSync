package app.snapsync.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

/**
 * The standalone **checkmark toggle row** ([AppSummaryToggle]) — the album opt-in idiom. One
 * `Role.Checkbox` node (not a switch: it commits with Join, not immediately), whose note carries the state
 * in words too. When [dimmed] the node stays in the tree as a **disabled** checkbox rather than vanishing.
 */
class AppSummaryToggleTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the row is a checkbox reflecting checked, and renders its note`() {
        var checked by mutableStateOf(false)
        rule.setContent {
            AppSummaryToggle(
                label = "Create an album",
                checked = checked,
                onCheckedChange = { checked = it },
                note = "No album is created.",
            )
        }
        rule.onNodeWithText("Create an album")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        rule.onNodeWithText("No album is created.").assertExists()

        rule.onNodeWithText("Create an album").performClick()
        assertEquals(true, checked)
        rule.onNodeWithText("Create an album")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    }

    @Test
    fun `a dimmed row stays present as a disabled checkbox with its note`() {
        rule.setContent {
            AppSummaryToggle(
                label = "Create an album",
                checked = false,
                onCheckedChange = {},
                note = "Turn on receiving to save arriving photos to an album.",
                dimmed = true,
            )
        }
        // Present, not silent — a control that reports "unavailable" beats one that disappears.
        rule.onNodeWithText("Create an album")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assertIsNotEnabled()
        rule.onNodeWithText("Turn on receiving to save arriving photos to an album.").assertExists()
    }
}
