package app.snapsync.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.snapsync.permission.PermissionStatus
import app.snapsync.presentation.UiState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

// A representative invite deeplink — any string renders a QR; the encoding is pinned in capability:config.
private const val SAMPLE_INVITE = "snapsync://config?v=3&d=eyJldmVudElkIjoiMSJ9"

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
    fun `permission blocked not-determined shows allow-access priming on the status screen`() {
        var requests = 0
        rule.setContent {
            StatusScreen(
                UiState.PermissionBlocked(PermissionStatus.NOT_DETERMINED),
                onRequestPermission = { requests++ },
            )
        }

        rule.onNodeWithText("Allow photo access").assertExists()
        rule.onNodeWithText("SnapSync needs your photo library to back it up.").assertExists()
        // No progress counts while blocked.
        rule.onNodeWithText("images synced", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Allow access").performClick()
        assertEquals(1, requests)
    }

    @Test
    fun `permission blocked denied shows the settings path on the status screen`() {
        var settingsOpens = 0
        rule.setContent {
            StatusScreen(
                UiState.PermissionBlocked(PermissionStatus.DENIED),
                onOpenSettings = { settingsOpens++ },
            )
        }

        rule.onNodeWithText("Photo access turned off").assertExists()
        rule.onNodeWithText("SnapSync needs photo access to continue backing up your library.").assertExists()
        rule.onNodeWithText("images synced", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Open Settings").performClick()
        assertEquals(1, settingsOpens)
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

    @Test
    fun `in progress shows the leave action`() {
        rule.setContent {
            StatusScreen(UiState.InProgress(synced = 3, total = 5, inProgress = 0, finishedAgo = "2 min ago"))
        }
        rule.onNodeWithContentDescription("Leave event").assertExists()
    }

    @Test
    fun `nothing to sync shows the leave action`() {
        rule.setContent { StatusScreen(UiState.NothingToSync) }
        rule.onNodeWithContentDescription("Leave event").assertExists()
    }

    @Test
    fun `completed shows the leave action`() {
        rule.setContent { StatusScreen(UiState.Completed(total = 47, finishedAgo = "5 min ago")) }
        rule.onNodeWithContentDescription("Leave event").assertExists()
    }

    @Test
    fun `loading hides the leave action`() {
        rule.setContent { StatusScreen(UiState.Loading) }
        rule.onNodeWithContentDescription("Leave event").assertDoesNotExist()
    }

    @Test
    fun `joining hides the leave action`() {
        rule.setContent { StatusScreen(UiState.Joining) }
        rule.onNodeWithContentDescription("Leave event").assertDoesNotExist()
    }

    @Test
    fun `join failed hides the leave action`() {
        rule.setContent { StatusScreen(UiState.JoinFailed) }
        rule.onNodeWithContentDescription("Leave event").assertDoesNotExist()
    }

    @Test
    fun `setup gate hides the leave action`() {
        rule.setContent {
            StatusScreen(UiState.Setup(storageConnected = true, permission = PermissionStatus.GRANTED))
        }
        rule.onNodeWithContentDescription("Leave event").assertDoesNotExist()
    }

    @Test
    fun `activating leave shows the confirm dialog`() {
        rule.setContent { StatusScreen(UiState.Completed(total = 47, finishedAgo = "5 min ago")) }

        rule.onNodeWithText("Leave event?").assertDoesNotExist()
        rule.onNodeWithContentDescription("Leave event").performClick()
        rule.onNodeWithText("Leave event?").assertExists()
    }

    @Test
    fun `confirming leave invokes the callback`() {
        var leaves = 0
        rule.setContent {
            StatusScreen(UiState.NothingToSync, onLeaveEvent = { leaves++ })
        }

        rule.onNodeWithContentDescription("Leave event").performClick()
        rule.onNodeWithText("Confirm").performClick()
        assertEquals(1, leaves)
    }

    @Test
    fun `cancelling leave does not invoke the callback and dismisses the dialog`() {
        var leaves = 0
        rule.setContent {
            StatusScreen(UiState.NothingToSync, onLeaveEvent = { leaves++ })
        }

        rule.onNodeWithContentDescription("Leave event").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, leaves)
        rule.onNodeWithText("Leave event?").assertDoesNotExist()
    }

    @Test
    fun `in progress shows the invite QR and share action`() {
        rule.setContent {
            StatusScreen(
                UiState.InProgress(synced = 3, total = 5, inProgress = 0, finishedAgo = "2 min ago"),
                inviteUrl = SAMPLE_INVITE,
            )
        }
        rule.onNodeWithText("Scan to join this event").assertExists()
        rule.onNodeWithContentDescription("Share invite link").assertExists()
    }

    @Test
    fun `nothing to sync shows the invite QR and share action`() {
        rule.setContent { StatusScreen(UiState.NothingToSync, inviteUrl = SAMPLE_INVITE) }
        rule.onNodeWithText("Scan to join this event").assertExists()
        rule.onNodeWithContentDescription("Share invite link").assertExists()
    }

    @Test
    fun `completed shows the invite QR and share action`() {
        rule.setContent {
            StatusScreen(UiState.Completed(total = 47, finishedAgo = "5 min ago"), inviteUrl = SAMPLE_INVITE)
        }
        rule.onNodeWithText("Scan to join this event").assertExists()
        rule.onNodeWithContentDescription("Share invite link").assertExists()
    }

    @Test
    fun `loading hides the invite affordances`() {
        rule.setContent { StatusScreen(UiState.Loading, inviteUrl = SAMPLE_INVITE) }
        rule.onNodeWithText("Scan to join this event").assertDoesNotExist()
        rule.onNodeWithContentDescription("Share invite link").assertDoesNotExist()
    }

    @Test
    fun `join failed hides the invite affordances`() {
        rule.setContent { StatusScreen(UiState.JoinFailed, inviteUrl = SAMPLE_INVITE) }
        rule.onNodeWithText("Scan to join this event").assertDoesNotExist()
        rule.onNodeWithContentDescription("Share invite link").assertDoesNotExist()
    }

    @Test
    fun `setup gate hides the invite affordances`() {
        rule.setContent {
            StatusScreen(
                UiState.Setup(storageConnected = true, permission = PermissionStatus.GRANTED),
                inviteUrl = SAMPLE_INVITE,
            )
        }
        rule.onNodeWithText("Scan to join this event").assertDoesNotExist()
        rule.onNodeWithContentDescription("Share invite link").assertDoesNotExist()
    }

    @Test
    fun `joined without an invite url hides the invite affordances`() {
        rule.setContent { StatusScreen(UiState.NothingToSync, inviteUrl = null) }
        rule.onNodeWithText("Scan to join this event").assertDoesNotExist()
        rule.onNodeWithContentDescription("Share invite link").assertDoesNotExist()
    }

    @Test
    fun `activating share invokes the callback`() {
        var shares = 0
        rule.setContent {
            StatusScreen(UiState.NothingToSync, onShareInvite = { shares++ }, inviteUrl = SAMPLE_INVITE)
        }
        rule.onNodeWithContentDescription("Share invite link").performClick()
        assertEquals(1, shares)
    }

    private fun hasAnyProgressIndication(): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
}
