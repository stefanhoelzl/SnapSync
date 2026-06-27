package app.snapsync.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.snapsync.permission.PermissionStatus
import app.snapsync.presentation.UiState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

class StatusScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `loading shows loading copy and an indeterminate indicator`() {
        rule.setContent { StatusScreen(UiState.Loading) }

        rule.onNodeWithText("Loading …").assertExists()
        rule.onNode(hasAnyProgressIndication()).assertExists()
    }

    @Test
    fun `joining shows the preparing copy and an indeterminate indicator`() {
        rule.setContent { StatusScreen(UiState.Joining) }

        rule.onNodeWithText("Checking what's already backed up …").assertExists()
        rule.onNode(hasAnyProgressIndication()).assertExists()
    }

    @Test
    fun `join failed shows the failure copy and a re-scan prompt with no spinner`() {
        rule.setContent { StatusScreen(UiState.JoinFailed) }

        rule.onNodeWithText("Couldn't reach the server").assertExists()
        rule.onNodeWithText("Scan the event QR code again").assertExists()
        rule.onNode(hasAnyProgressIndication()).assertDoesNotExist()
    }

    @Test
    fun `unconnected storage shows the scan instruction`() {
        rule.setContent {
            StatusScreen(UiState.Setup(storageConnected = false, permission = PermissionStatus.GRANTED))
        }

        rule.onNodeWithText("Connect your storage").assertExists()
        rule.onNodeWithText("Open the Camera app and scan your SnapSync QR code.").assertExists()
    }

    @Test
    fun `invalid deeplink error replaces the storage instruction`() {
        rule.setContent {
            StatusScreen(
                UiState.Setup(storageConnected = false, permission = PermissionStatus.GRANTED),
                transientError = "That QR code wasn't valid.",
            )
        }

        rule.onNodeWithText("That QR code wasn't valid.").assertExists()
    }

    @Test
    fun `permission ask shows invitation copy and the allow action`() {
        var requests = 0
        rule.setContent {
            StatusScreen(
                UiState.Setup(storageConnected = true, permission = PermissionStatus.NOT_DETERMINED),
                onRequestPermission = { requests++ },
            )
        }

        rule.onNodeWithText("Storage connected").assertExists()
        rule.onNodeWithText("Allow photo access").assertExists()
        rule.onNodeWithText("Allow access").performClick()
        assertEquals(1, requests)
    }

    @Test
    fun `permission denied shows problem copy and the settings action`() {
        var settingsOpens = 0
        rule.setContent {
            StatusScreen(
                UiState.Setup(storageConnected = true, permission = PermissionStatus.DENIED),
                onOpenSettings = { settingsOpens++ },
            )
        }

        rule.onNodeWithText("Photo access denied").assertExists()
        rule.onNodeWithText("Turn on photo access in Settings.").assertExists()
        rule.onNodeWithText("Open Settings").performClick()
        assertEquals(1, settingsOpens)
    }

    @Test
    fun `setup gate shows no sync status hero`() {
        rule.setContent {
            StatusScreen(UiState.Setup(storageConnected = true, permission = PermissionStatus.DENIED))
        }

        rule.onNodeWithText("images synced", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Nothing to sync yet").assertDoesNotExist()
        rule.onNode(hasAnyProgressIndication()).assertDoesNotExist()
    }

    @Test
    fun `in progress shows the n of N count and the in-progress count with last-sync time`() {
        rule.setContent {
            StatusScreen(UiState.InProgress(synced = 12, total = 47, inProgress = 35, finishedAgo = "5 min ago"))
        }

        rule.onNodeWithText("12 of 47 images synced").assertExists()
        // Second caption: in-progress count and the last-sync age on one merged line.
        rule.onNodeWithText("35 in progress · 5 min ago").assertExists()
        // The LED dot is not a progress indicator (no spinner, no ring).
        rule.onNode(hasAnyProgressIndication()).assertDoesNotExist()
    }

    @Test
    fun `in progress with no prior completion shows the in-progress count and no time`() {
        rule.setContent {
            StatusScreen(UiState.InProgress(synced = 0, total = 47, inProgress = 47, finishedAgo = null))
        }

        rule.onNodeWithText("0 of 47 images synced").assertExists()
        // At a virgin "0 of N" the second caption is just the count — no "· x min ago".
        rule.onNodeWithText("47 in progress").assertExists()
    }

    @Test
    fun `in progress with nothing actively uploading omits the in-progress label`() {
        rule.setContent {
            StatusScreen(UiState.InProgress(synced = 3, total = 5, inProgress = 0, finishedAgo = "2 min ago"))
        }

        rule.onNodeWithText("3 of 5 images synced").assertExists()
        // 0 actively uploading → no "0 in progress" noise, just the last-sync age.
        rule.onNodeWithText("2 min ago").assertExists()
        rule.onNodeWithText("in progress", substring = true).assertDoesNotExist()
    }

    @Test
    fun `nothing to sync shows the idle line`() {
        rule.setContent { StatusScreen(UiState.NothingToSync) }

        rule.onNodeWithText("SnapSync").assertExists()
        rule.onNodeWithText("Nothing to sync yet").assertExists()
        rule.onNode(hasAnyProgressIndication()).assertDoesNotExist()
    }

    @Test
    fun `completed shows the total and relative time`() {
        rule.setContent { StatusScreen(UiState.Completed(total = 47, finishedAgo = "5 min ago")) }

        rule.onNodeWithText("47 images synced").assertExists()
        rule.onNodeWithText("5 min ago").assertExists()
        rule.onNode(hasAnyProgressIndication()).assertDoesNotExist()
    }

    private fun hasAnyProgressIndication(): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
}
