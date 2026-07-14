package app.snapsync.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.JoinPhase
import app.snapsync.presentation.PendingSwitch
import app.snapsync.presentation.SyncHealth
import app.snapsync.presentation.UiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDateTime
import org.junit.Rule

/** The loaded phase always carries a cutoff — the host resolves an absent `createdAt` to now. */
private const val CUTOFF = "2026-07-06T14:32:11Z"

class JoinScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun joining(phase: JoinPhase) = UiState.JoiningEvent("11111111-1111-4111-8111-111111111111", phase)

    /** Deterministic formatter so the cutoff field renders a known string to click. */
    private class FixedCutoffFormatter : CutoffFormatter {
        override fun nowLocal() = LocalDateTime(2026, 7, 6, 12, 0)
        override fun toCutoff(local: LocalDateTime) = "2026-07-06T12:00:00Z"
        override fun toLocal(cutoff: String) = LocalDateTime(2026, 7, 4, 18, 0)
    }

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
            StatusScreen(joining(JoinPhase.Ready("Anna's Wedding", CUTOFF)), onConfirmJoin = { _, _, _ -> joined++ })
        }
        rule.onNodeWithText("Anna's Wedding").assertExists()
        rule.onNodeWithText("Join").assertExists()
        rule.onNodeWithText("Cancel").assertExists()
        rule.onNodeWithText("Join").performClick()
        assertEquals(1, joined)
    }

    @Test
    fun `ready phase shows the capture-date cutoff row`() {
        rule.setContent { StatusScreen(joining(JoinPhase.Ready("Anna's Wedding", "2026-07-04T18:00:00Z"))) }
        // The cutoff row: a caption and the "Only from now" shortcut (capability photo-date-cutoff).
        rule.onNodeWithText("Only photos taken after this date are shared to the event.").assertExists()
        rule.onNodeWithText("Only from now").assertExists()
    }

    @Test
    fun `tapping the cutoff field opens the date picker`() {
        // Regression: a read-only OutlinedTextField swallows a `.clickable`, so the picker must open via
        // the field's press interaction. The fixed formatter renders the field as "2026-07-04 18:00".
        rule.setContent {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", "2026-07-04T18:00:00Z")),
                cutoff = FixedCutoffFormatter(),
            )
        }
        rule.onNodeWithText("Next").assertDoesNotExist() // dialog not shown yet
        rule.onNodeWithText("2026-07-04 18:00").performClick()
        rule.onNodeWithText("Next").assertExists() // the DatePickerDialog's confirm button appeared
    }

    @Test
    fun `ready phase shows the arrows-only direction selector with an adaptive caption`() {
        rule.setContent { StatusScreen(joining(JoinPhase.Ready("Anna's Wedding", CUTOFF))) }
        // Arrows, not words: the three options carry content descriptions, no "Both"/"Upload only" text.
        rule.onNodeWithContentDescription("Share and receive").assertExists()
        rule.onNodeWithContentDescription("Only share").assertExists()
        rule.onNodeWithContentDescription("Only receive").assertExists()
        rule.onNodeWithText("Upload only").assertDoesNotExist()
        // Default (Both) caption; selecting an arrow adapts the caption above.
        rule.onNodeWithText("Share your photos and receive the event's photos.").assertExists()
        rule.onNodeWithContentDescription("Only receive").performClick()
        rule.onNodeWithText("Only receive the event's photos — you won't share yours.").assertExists()
        rule.onNodeWithContentDescription("Only share").performClick()
        rule.onNodeWithText("Only share your photos — you won't receive the event's.").assertExists()
    }

    @Test
    fun `selecting download-only disables the cutoff shortcut, and re-enabling restores it`() {
        rule.setContent {
            StatusScreen(joining(JoinPhase.Ready("Anna's Wedding", "2026-07-04T18:00:00Z")))
        }
        // Default Both → the cutoff shortcut is enabled.
        rule.onNodeWithText("Only from now").assertIsEnabled()
        // Download-only (the down arrow) scopes no uploads → the cutoff row goes inert.
        rule.onNodeWithContentDescription("Only receive").performClick()
        rule.onNodeWithText("Only from now").assertIsNotEnabled()
        // Switching back to upload (the up arrow) re-enables it.
        rule.onNodeWithContentDescription("Only share").performClick()
        rule.onNodeWithText("Only from now").assertIsEnabled()
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
        rule.setContent { StatusScreen(joining(JoinPhase.CommitFailed("Anna's Wedding")), onRetryJoin = { _, _, _ -> retried++ }) }
        // (CommitFailed still carries only the name; the cutoff is held in the screen's own state.)
        rule.onNodeWithText("Couldn't join").assertExists()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    // ---- the photo-access explainer (capability `join-event`) -----------------------------------------

    @Test
    fun `explain-access phase shows the access copy with I understand and Cancel`() {
        rule.setContent { StatusScreen(joining(JoinPhase.ExplainAccess("Anna's Wedding", CUTOFF))) }

        rule.onNodeWithText("Photo access").assertExists()
        rule.onNodeWithText("Photos you take will be shared automatically with everyone in the event.")
            .assertExists()
        rule.onNodeWithText("Only photos taken after the date you pick next are shared.").assertExists()
        rule.onNodeWithText("I understand").assertExists()
        rule.onNodeWithText("Cancel").assertExists()
        // The explainer precedes the confirm surface: no Join, and none of the join rows yet.
        rule.onNodeWithText("Join").assertDoesNotExist()
        rule.onNodeWithText("Only from now").assertDoesNotExist()
        // The event is deliberately NOT named — this is a statement about what the app does.
        rule.onNodeWithText("Anna's Wedding").assertDoesNotExist()
    }

    @Test
    fun `I understand acknowledges the explainer`() {
        var acknowledged = 0
        rule.setContent {
            StatusScreen(
                joining(JoinPhase.ExplainAccess("Anna's Wedding", CUTOFF)),
                onAcknowledgeAccess = { acknowledged++ },
            )
        }
        rule.onNodeWithText("I understand").performClick()
        assertEquals(1, acknowledged)
    }

    @Test
    fun `cancelling the explainer abandons the join`() {
        var cancelled = 0
        rule.setContent {
            StatusScreen(
                joining(JoinPhase.ExplainAccess("Anna's Wedding", CUTOFF)),
                onCancelJoin = { cancelled++ },
            )
        }
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
    }

    /**
     * REGRESSION. The cutoff row must seed from the loaded `createdAt`, and the screen does **not** mount at
     * a phase that carries one: the real flow is `Loading` → (`ExplainAccess`) → `Ready`. Seeding on first
     * composition therefore fell through to `now` and `remember` never re-ran, so the row silently defaulted
     * to now for every real join — defeating "a prefilled cutoff value (defaulting to the loaded
     * `createdAt`)". Every other test in this file mounts straight into `Ready`, which is exactly why none
     * of them caught it.
     */
    @Test
    fun `the cutoff row seeds from the loaded default across the real phase sequence`() {
        var phase by mutableStateOf<JoinPhase>(JoinPhase.Loading)
        rule.setContent {
            StatusScreen(joining(phase), cutoff = FixedCutoffFormatter())
        }
        rule.onNodeWithText("Loading event details …").assertExists()

        // The details fetch resolves; permission was never asked, so the explainer comes first.
        phase = JoinPhase.ExplainAccess("Anna's Wedding", "2026-07-04T18:00:00Z")
        rule.waitForIdle()
        rule.onNodeWithText("I understand").assertExists()

        // "I understand" → the confirm surface. The row shows the EVENT'S createdAt (2026-07-04 18:00),
        // not "now" (which this formatter renders as 2026-07-06 12:00).
        phase = JoinPhase.Ready("Anna's Wedding", "2026-07-04T18:00:00Z")
        rule.waitForIdle()
        rule.onNodeWithText("2026-07-04 18:00").assertExists()
    }

    @Test
    fun `switch dialog shows leave-and-join copy and confirms`() {
        var switched = 0
        rule.setContent {
            StatusScreen(
                UiState.Joined(SyncHealth.Loading, PendingSwitch("22222222-2222-4222-8222-222222222222", JoinPhase.Ready("New Event", CUTOFF))),
                eventName = "Summer Trip",
                onConfirmSwitch = { _, _ -> switched++ },
            )
        }
        rule.onNodeWithText("Leave Summer Trip and join New Event?").assertExists()
        rule.onNodeWithText("Switch").performClick()
        assertEquals(1, switched)
    }
}
