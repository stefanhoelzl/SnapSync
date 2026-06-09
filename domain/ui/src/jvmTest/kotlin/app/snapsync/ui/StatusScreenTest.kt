package app.snapsync.ui

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.snapsync.presentation.UiState
import kotlin.test.Test
import org.junit.Rule

class StatusScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `idle state shows title and idle status with no progress indication`() {
        rule.setContent { StatusScreen(UiState.Idle) }

        rule.onNodeWithText("SnapSync").assertExists()
        rule.onNodeWithText("Up to date").assertExists()
        rule.onNode(hasAnyProgressIndication()).assertDoesNotExist()
    }

    @Test
    fun `uploading state shows progress 3 of 10 at 30 percent`() {
        rule.setContent { StatusScreen(UiState.Uploading(done = 3, total = 10)) }

        rule.onNodeWithText("Uploading…").assertExists()
        rule.onNodeWithText("3 of 10").assertExists()
        rule.onNode(hasProgress(0.3f)).assertExists()
    }

    private fun hasAnyProgressIndication(): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private fun hasProgress(fraction: Float): SemanticsMatcher =
        SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(current = fraction, range = 0f..1f),
        )
}
