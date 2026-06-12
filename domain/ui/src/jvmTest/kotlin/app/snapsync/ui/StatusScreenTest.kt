package app.snapsync.ui

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.snapsync.presentation.UiState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

class StatusScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `permission ask shows invitation copy and the allow action`() {
        var requests = 0
        rule.setContent {
            StatusScreen(UiState.PermissionAsk, onRequestPermission = { requests++ })
        }

        rule.onNodeWithText("Sync your photos").assertExists()
        rule.onNodeWithText("SnapSync needs access to your photo library").assertExists()
        rule.onNodeWithText("Allow access").performClick()
        assertEquals(1, requests)
    }

    @Test
    fun `permission denied shows problem copy and the settings action`() {
        var settingsOpens = 0
        rule.setContent {
            StatusScreen(UiState.PermissionDenied, onOpenSettings = { settingsOpens++ })
        }

        rule.onNodeWithText("Photo access denied").assertExists()
        rule.onNodeWithText("Turn on photo access in system settings").assertExists()
        rule.onNodeWithText("Open Settings").performClick()
        assertEquals(1, settingsOpens)
    }

    @Test
    fun `gate states show no sync status hero`() {
        rule.setContent { StatusScreen(UiState.PermissionDenied) }

        rule.onNodeWithText("Sync failed").assertDoesNotExist()
        rule.onNodeWithText("No sync yet").assertDoesNotExist()
        rule.onNode(hasAnyProgressIndication()).assertDoesNotExist()
    }

    @Test
    fun `never synced shows bare warning headline`() {
        rule.setContent { StatusScreen(UiState.NeverSynced) }

        rule.onNodeWithText("SnapSync").assertExists()
        rule.onNodeWithText("No sync yet").assertExists()
        rule.onNode(hasAnyProgressIndication()).assertDoesNotExist()
    }

    @Test
    fun `in progress shows headline, estimate, and indicator fraction without textual counts`() {
        rule.setContent { StatusScreen(UiState.InProgress(fraction = 0.35f, estimate = "~2 min left")) }

        rule.onNodeWithText("Sync in progress").assertExists()
        rule.onNodeWithText("~2 min left").assertExists()
        rule.onNode(hasProgress(0.35f)).assertExists()
        rule.onNodeWithText("of", substring = true).assertDoesNotExist()
    }

    @Test
    fun `suspended shows bare waiting headline`() {
        rule.setContent { StatusScreen(UiState.Suspended) }

        rule.onNodeWithText("Waiting to sync").assertExists()
        rule.onNode(hasAnyProgressIndication()).assertDoesNotExist()
    }

    @Test
    fun `complete shows headline with relative time`() {
        rule.setContent { StatusScreen(UiState.Complete(finishedAgo = "5 min ago")) }

        rule.onNodeWithText("Sync complete").assertExists()
        rule.onNodeWithText("5 min ago").assertExists()
    }

    @Test
    fun `incomplete shows headline with relative time`() {
        rule.setContent { StatusScreen(UiState.Incomplete(finishedAgo = "5 min ago")) }

        rule.onNodeWithText("Sync incomplete").assertExists()
        rule.onNodeWithText("5 min ago").assertExists()
    }

    private fun hasAnyProgressIndication(): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private fun hasProgress(fraction: Float): SemanticsMatcher =
        SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(current = fraction, range = 0f..1f),
        )
}
