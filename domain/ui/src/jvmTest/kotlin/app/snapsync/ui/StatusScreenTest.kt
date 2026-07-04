package app.snapsync.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.snapsync.permission.PermissionStatus
import app.snapsync.presentation.Arrow
import app.snapsync.presentation.SyncHealth
import app.snapsync.presentation.UiState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

// A representative invite deeplink — any string renders a QR; the encoding is pinned in capability:config.
private const val SAMPLE_INVITE = "snapsync://config?v=3&d=eyJldmVudElkIjoiMSJ9"

private fun joined(health: SyncHealth) = UiState.Joined(health)
private val inSync = joined(SyncHealth.InSync)
private val syncing = joined(SyncHealth.Syncing(Arrow.PULSING, Arrow.HIDDEN))

class StatusScreenTest {

    @get:Rule
    val rule = createComposeRule()

    // ---- create layer ----

    @Test
    fun `create screen shows the name input and the scan-to-join hint`() {
        rule.setContent { StatusScreen(UiState.CreateEvent()) }

        rule.onNodeWithText("Start an event").assertExists()
        rule.onNodeWithText("Or scan a QR code in the Camera app to join one.").assertExists()
        rule.onNodeWithText("Event name").assertExists()
        rule.onNodeWithText("Create event").assertExists()
    }

    @Test
    fun `invalid deeplink error shows on the create screen`() {
        rule.setContent {
            StatusScreen(UiState.CreateEvent(), transientError = "That QR code wasn't valid.")
        }
        rule.onNodeWithText("That QR code wasn't valid.").assertExists()
    }

    @Test
    fun `a create failure shows its inline error on the create screen`() {
        rule.setContent { StatusScreen(UiState.CreateEvent(error = "Couldn't reach the server.")) }
        rule.onNodeWithText("Couldn't reach the server.").assertExists()
    }

    @Test
    fun `create is disabled until a name is typed`() {
        rule.setContent { StatusScreen(UiState.CreateEvent()) }

        rule.onNodeWithText("Create event").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("My Party")
        rule.onNodeWithText("Create event").assertIsEnabled()
    }

    @Test
    fun `tapping create submits the typed name`() {
        var created: String? = null
        rule.setContent { StatusScreen(UiState.CreateEvent(), onCreateEvent = { created = it }) }

        rule.onNode(hasSetTextAction()).performTextInput("My Party")
        rule.onNodeWithText("Create event").performClick()
        assertEquals("My Party", created)
    }

    @Test
    fun `the name field caps at 100 characters`() {
        rule.setContent { StatusScreen(UiState.CreateEvent()) }

        val field = rule.onNode(hasSetTextAction())
        field.performTextInput("a".repeat(100))
        field.performTextInput("b")
        val text = field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals(100, text.length)
    }

    @Test
    fun `create layer shows no sync line, leave, or invite`() {
        rule.setContent { StatusScreen(UiState.CreateEvent(), inviteUrl = SAMPLE_INVITE) }

        rule.onNodeWithText("In sync").assertDoesNotExist()
        rule.onNodeWithText("Syncing…").assertDoesNotExist()
        rule.onNodeWithContentDescription("Leave event").assertDoesNotExist()
        rule.onNodeWithText("Scan to join this event").assertDoesNotExist()
    }

    @Test
    fun `creating event shows a preparing indicator and no input`() {
        rule.setContent { StatusScreen(UiState.CreatingEvent) }

        rule.onNodeWithText("Creating your event …").assertExists()
        rule.onNode(hasAnyProgressIndication()).assertExists()
        rule.onNodeWithText("Event name").assertDoesNotExist()
    }

    // ---- joined layer: status line ----

    @Test
    fun `in sync shows the settled line and no counts`() {
        rule.setContent { StatusScreen(inSync) }

        rule.onNodeWithText("In sync").assertExists()
        rule.onNodeWithText("images synced", substring = true).assertDoesNotExist()
    }

    @Test
    fun `syncing shows the syncing line`() {
        rule.setContent { StatusScreen(syncing) }

        rule.onNodeWithText("Syncing…").assertExists()
        rule.onNodeWithText("images synced", substring = true).assertDoesNotExist()
    }

    @Test
    fun `needs-access not-determined shows the allow copy and taps request permission`() {
        var requests = 0
        rule.setContent {
            StatusScreen(
                joined(SyncHealth.NeedsAccess(PermissionStatus.NOT_DETERMINED)),
                onRequestPermission = { requests++ },
            )
        }

        rule.onNodeWithText("Allow photo access").assertExists()
        rule.onNodeWithText("Allow photo access").performClick()
        assertEquals(1, requests)
    }

    @Test
    fun `needs-access denied shows the settings copy and taps open settings`() {
        var settingsOpens = 0
        rule.setContent {
            StatusScreen(
                joined(SyncHealth.NeedsAccess(PermissionStatus.DENIED)),
                onOpenSettings = { settingsOpens++ },
            )
        }

        rule.onNodeWithText("Turn on full access in Settings").assertExists()
        rule.onNodeWithText("Turn on full access in Settings").performClick()
        assertEquals(1, settingsOpens)
    }

    // ---- joined layer: name, leave, invite ----

    @Test
    fun `joined shows the event name as the title`() {
        rule.setContent { StatusScreen(inSync, eventName = "Anna's Birthday") }
        rule.onNodeWithText("Anna's Birthday").assertExists()
    }

    @Test
    fun `joined shows the leave action`() {
        rule.setContent { StatusScreen(inSync) }
        rule.onNodeWithContentDescription("Leave event").assertExists()
    }

    @Test
    fun `needs-access still shows leave and invite (sharing needs no access)`() {
        rule.setContent {
            StatusScreen(
                joined(SyncHealth.NeedsAccess(PermissionStatus.DENIED)),
                inviteUrl = SAMPLE_INVITE,
            )
        }
        rule.onNodeWithContentDescription("Leave event").assertExists()
        rule.onNodeWithText("Scan to join this event").assertExists()
        rule.onNodeWithContentDescription("Share invite link").assertExists()
    }

    @Test
    fun `activating leave shows the leave-this-event dialog`() {
        rule.setContent { StatusScreen(inSync) }

        rule.onNodeWithText("Leave this event?").assertDoesNotExist()
        rule.onNodeWithContentDescription("Leave event").performClick()
        rule.onNodeWithText("Leave this event?").assertExists()
    }

    @Test
    fun `confirming leave invokes the callback`() {
        var leaves = 0
        rule.setContent { StatusScreen(inSync, onLeaveEvent = { leaves++ }) }

        rule.onNodeWithContentDescription("Leave event").performClick()
        rule.onNodeWithText("Leave").performClick()
        assertEquals(1, leaves)
    }

    @Test
    fun `staying does not invoke leave and dismisses the dialog`() {
        var leaves = 0
        rule.setContent { StatusScreen(inSync, onLeaveEvent = { leaves++ }) }

        rule.onNodeWithContentDescription("Leave event").performClick()
        rule.onNodeWithText("Stay").performClick()
        assertEquals(0, leaves)
        rule.onNodeWithText("Leave this event?").assertDoesNotExist()
    }

    @Test
    fun `joined shows the invite QR and share action`() {
        rule.setContent { StatusScreen(inSync, inviteUrl = SAMPLE_INVITE) }
        rule.onNodeWithText("Scan to join this event").assertExists()
        rule.onNodeWithContentDescription("Share invite link").assertExists()
    }

    @Test
    fun `joined without an invite url hides the invite affordances`() {
        rule.setContent { StatusScreen(inSync, inviteUrl = null) }
        rule.onNodeWithText("Scan to join this event").assertDoesNotExist()
        rule.onNodeWithContentDescription("Share invite link").assertDoesNotExist()
    }

    @Test
    fun `activating share invokes the callback`() {
        var shares = 0
        rule.setContent { StatusScreen(inSync, onShareInvite = { shares++ }, inviteUrl = SAMPLE_INVITE) }
        rule.onNodeWithContentDescription("Share invite link").performClick()
        assertEquals(1, shares)
    }

    private fun hasAnyProgressIndication(): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
}
