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
import app.snapsync.presentation.JoinPhase
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.PendingSwitch
import app.snapsync.presentation.SyncHealth
import app.snapsync.presentation.UiState
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.TimeZone
import org.junit.Rule

/** The loaded phase always carries a cutoff — the host resolves an absent `createdAt` to now. */
private const val CUTOFF = "2026-07-06T14:32:11Z"

/** "Now" for the fixed test clock. */
private const val NOW = "2026-07-06T12:00:00Z"

/** An event that has ALREADY started (before [NOW]) — the ordinary case. */
private const val EVENT_START = "2026-07-04T18:00:00Z"

/** An event that has NOT started yet (after [NOW]) — where the "Now" preset collapses onto the floor. */
private const val FUTURE_START = "2026-07-09T18:00:00Z"

class JoinScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun joining(phase: JoinPhase) = UiState.JoiningEvent("11111111-1111-4111-8111-111111111111", phase)

    /**
     * A REAL formatter on a fixed clock (UTC), not a constant-returning stub: the join surface now decides
     * whether the event has started by comparing `startsAt` against "now", so a formatter that ignores its
     * input could not express the pre-start case at all.
     */
    private fun fixedCutoff(now: String = NOW) = CutoffFormatter(
        now = { Instant.parse(now) },
        zone = TimeZone.UTC,
    )

    @Test
    fun `loading phase shows the loading label and no Join`() {
        rule.setContent { StatusScreen(joining(JoinPhase.Loading), cutoff = fixedCutoff()) }
        rule.onNodeWithText("Loading event details …").assertExists()
        rule.onNodeWithText("Join").assertDoesNotExist()
    }

    @Test
    fun `ready phase shows the event name with Join and Cancel`() {
        var joined = 0
        rule.setContent {
            StatusScreen(joining(JoinPhase.Ready("Anna's Wedding", CUTOFF)), onConfirmJoin = { _, _, _ -> joined++ }, cutoff = fixedCutoff())
        }
        rule.onNodeWithText("Anna's Wedding").assertExists()
        rule.onNodeWithText("Join").assertExists()
        rule.onNodeWithText("Cancel").assertExists()
        rule.onNodeWithText("Join").performClick()
        assertEquals(1, joined)
    }

    @Test
    fun `ready phase shows the two-preset cutoff row with the event start as the default`() {
        rule.setContent {
            StatusScreen(joining(JoinPhase.Ready("Anna's Wedding", EVENT_START)), cutoff = fixedCutoff())
        }
        // The cutoff row: a caption, the two presets, and the RESULTING instant as a label — so the member
        // always sees the value they are committing to (capability photo-selection-policy).
        rule.onNodeWithText("Only photos taken after this date are shared to the event.").assertExists()
        rule.onNodeWithText("Now").assertExists()
        rule.onNodeWithText("Event start").assertExists()
        // Default = Event start (4 Jul 18:00), NOT now (6 Jul 12:00).
        rule.onNodeWithText("From 4 Jul 2026, 18:00").assertExists()
    }

    @Test
    fun `selecting Now moves the cutoff to the current instant`() {
        rule.setContent {
            StatusScreen(joining(JoinPhase.Ready("Anna's Wedding", EVENT_START)), cutoff = fixedCutoff())
        }
        rule.onNodeWithText("Now").performClick()
        rule.onNodeWithText("From 6 Jul 2026, 12:00").assertExists()
    }

    @Test
    fun `before the event starts the Now preset is disabled`() {
        // Pre-start, "Now" would clamp to the very same instant as "Event start" — so it is offered
        // disabled rather than as a button that visibly does nothing.
        rule.setContent {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", FUTURE_START)),
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("Now").assertIsNotEnabled()
        rule.onNodeWithText("Event start").assertIsEnabled()
        rule.onNodeWithText("From 9 Jul 2026, 18:00").assertExists()
    }

    @Test
    fun `after the event has started the Now preset is enabled`() {
        rule.setContent {
            StatusScreen(joining(JoinPhase.Ready("Anna's Wedding", EVENT_START)), cutoff = fixedCutoff())
        }
        rule.onNodeWithText("Now").assertIsEnabled()
    }

    @Test
    fun `ready phase shows the arrows-only direction selector with an adaptive caption`() {
        rule.setContent { StatusScreen(joining(JoinPhase.Ready("Anna's Wedding", CUTOFF)), cutoff = fixedCutoff()) }
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
    fun `selecting download-only disables the cutoff row and re-enabling restores it`() {
        rule.setContent {
            StatusScreen(joining(JoinPhase.Ready("Anna's Wedding", EVENT_START)), cutoff = fixedCutoff())
        }
        // Default Both → the cutoff presets are live.
        rule.onNodeWithText("Event start").assertIsEnabled()
        // Download-only (the down arrow) scopes no uploads → the cutoff row goes inert.
        rule.onNodeWithContentDescription("Only receive").performClick()
        rule.onNodeWithText("Event start").assertIsNotEnabled()
        rule.onNodeWithText("Now").assertIsNotEnabled()
        // Switching back to upload (the up arrow) re-enables it.
        rule.onNodeWithContentDescription("Only share").performClick()
        rule.onNodeWithText("Event start").assertIsEnabled()
    }

    @Test
    fun `not-found phase blocks the join`() {
        rule.setContent { StatusScreen(joining(JoinPhase.NotFound), cutoff = fixedCutoff()) }
        rule.onNodeWithText("Invalid invite").assertExists()
        rule.onNodeWithText("Join").assertDoesNotExist()
        rule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun `load-failed phase offers Retry`() {
        var retried = 0
        rule.setContent { StatusScreen(joining(JoinPhase.LoadFailed), onRetryLoad = { retried++ }, cutoff = fixedCutoff()) }
        rule.onNodeWithText("Retry").assertExists()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `commit-failed phase offers Retry for the join`() {
        var retried = 0
        rule.setContent {
            StatusScreen(
                joining(JoinPhase.CommitFailed("Anna's Wedding", EVENT_START)),
                onRetryJoin = { _, _, _ -> retried++ },
             cutoff = fixedCutoff())
        }
        // CommitFailed carries the startsAt too, so a Retry — which commits WITHOUT passing back through
        // the loaded phase — still has the floor.
        rule.onNodeWithText("Couldn't join").assertExists()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    // ---- the photo-access explainer (capability `join-event`) -----------------------------------------

    @Test
    fun `explain-access phase shows the access copy with I understand and Cancel`() {
        rule.setContent { StatusScreen(joining(JoinPhase.ExplainAccess("Anna's Wedding", CUTOFF)), cutoff = fixedCutoff()) }

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
             cutoff = fixedCutoff())
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
             cutoff = fixedCutoff())
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
    fun `the cutoff row shows the event start across the real phase sequence`() {
        var phase by mutableStateOf<JoinPhase>(JoinPhase.Loading)
        rule.setContent {
            StatusScreen(joining(phase), cutoff = fixedCutoff())
        }
        rule.onNodeWithText("Loading event details …").assertExists()

        // The details fetch resolves; permission was never asked, so the explainer comes first.
        phase = JoinPhase.ExplainAccess("Anna's Wedding", EVENT_START)
        rule.waitForIdle()
        rule.onNodeWithText("I understand").assertExists()

        // "I understand" → the confirm surface. The row shows the EVENT'S start (4 Jul 18:00), NOT "now"
        // (which this clock puts at 6 Jul 12:00). The row derives its instant from the phase on every
        // composition — nothing is captured at mount, so nothing can be stale.
        phase = JoinPhase.Ready("Anna's Wedding", EVENT_START)
        rule.waitForIdle()
        rule.onNodeWithText("From 4 Jul 2026, 18:00").assertExists()
    }

    @Test
    fun `a retry after a failed commit still carries the event start, not now`() {
        // The commit phases carry `startsAt` for exactly this reason: a retry commits WITHOUT passing back
        // through the loaded phase. A surface that could read the start only from `Ready` would derive the
        // retry's cutoff from `now` — silently discarding the user's choice at the one moment they are
        // already recovering from a failure.
        var retried: String? = null
        rule.setContent {
            StatusScreen(
                joining(JoinPhase.CommitFailed("Anna's Wedding", EVENT_START)),
                onRetryJoin = { cutoff, _, _ -> retried = cutoff },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("Retry").performClick()
        assertEquals(EVENT_START, retried, "the retry must carry the event start, not now")
    }

    @Test
    fun `switch dialog shows leave-and-join copy and confirms`() {
        var switched = 0
        rule.setContent {
            StatusScreen(
                UiState.Joined(SyncHealth.Loading, PendingSwitch("22222222-2222-4222-8222-222222222222", JoinPhase.Ready("New Event", CUTOFF))),
                eventName = "Summer Trip",
                onConfirmSwitch = { _, _ -> switched++ },
             cutoff = fixedCutoff())
        }
        rule.onNodeWithText("Leave Summer Trip and join New Event?").assertExists()
        rule.onNodeWithText("Switch").performClick()
        assertEquals(1, switched)
    }

    @Test
    fun `switch dialog for a missing event stays a plain confirmation and cancels`() {
        // Only the Ready "Switch" (which leaves the current event) is destructive; the non-destructive
        // phases — invalid invite here, and the retry phases — must keep the plain AppConfirmDialog.
        // Guards that the shared dialog-scaffold refactor did not sweep every phase into the destructive path.
        var cancelled = 0
        rule.setContent {
            StatusScreen(
                UiState.Joined(SyncHealth.Loading, PendingSwitch("22222222-2222-4222-8222-222222222222", JoinPhase.NotFound)),
                onCancelSwitch = { cancelled++ },
             cutoff = fixedCutoff())
        }
        rule.onNodeWithText("This invite is invalid or the event no longer exists.").assertExists()
        rule.onNodeWithText("OK").performClick()
        assertEquals(1, cancelled)
    }
}
