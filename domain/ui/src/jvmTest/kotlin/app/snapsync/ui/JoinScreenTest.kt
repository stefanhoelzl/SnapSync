package app.snapsync.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.snapsync.presentation.JoinPhase
import app.snapsync.presentation.PendingSwitch
import app.snapsync.presentation.SyncHealth
import app.snapsync.presentation.UiState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

class JoinScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun joining(phase: JoinPhase) = UiState.JoiningEvent("11111111-1111-4111-8111-111111111111", phase)

    @Test
    fun `loading phase shows the loading label and no Join`() {
        rule.setContent { StatusScreen(joining(JoinPhase.Loading)) }
        rule.onNodeWithText("Loading event details …").assertExists()
        rule.onNodeWithText("Join").assertDoesNotExist()
    }

    @Test
    fun `ready phase shows the event name with Join and Cancel`() {
        var joined = 0
        rule.setContent {
            StatusScreen(joining(JoinPhase.Ready("Anna's Wedding")), onConfirmJoin = { joined++ })
        }
        rule.onNodeWithText("Anna's Wedding").assertExists()
        rule.onNodeWithText("Join").assertExists()
        rule.onNodeWithText("Cancel").assertExists()
        rule.onNodeWithText("Join").performClick()
        assertEquals(1, joined)
    }

    @Test
    fun `not-found phase blocks the join`() {
        rule.setContent { StatusScreen(joining(JoinPhase.NotFound)) }
        rule.onNodeWithText("Invalid invite").assertExists()
        rule.onNodeWithText("Join").assertDoesNotExist()
        rule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun `load-failed phase offers Retry`() {
        var retried = 0
        rule.setContent { StatusScreen(joining(JoinPhase.LoadFailed), onRetryLoad = { retried++ }) }
        rule.onNodeWithText("Retry").assertExists()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `commit-failed phase offers Retry for the join`() {
        var retried = 0
        rule.setContent { StatusScreen(joining(JoinPhase.CommitFailed("Anna's Wedding")), onRetryJoin = { retried++ }) }
        rule.onNodeWithText("Couldn't join").assertExists()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `switch dialog shows leave-and-join copy and confirms`() {
        var switched = 0
        rule.setContent {
            StatusScreen(
                UiState.Joined(SyncHealth.Loading, PendingSwitch("22222222-2222-4222-8222-222222222222", JoinPhase.Ready("New Event"))),
                eventName = "Summer Trip",
                onConfirmSwitch = { switched++ },
            )
        }
        rule.onNodeWithText("Leave Summer Trip and join New Event?").assertExists()
        rule.onNodeWithText("Switch").performClick()
        assertEquals(1, switched)
    }
}
