package app.snapsync.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.JoinPhase
import app.snapsync.presentation.SyncHealth
import app.snapsync.presentation.UiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import org.junit.Rule

/**
 * The hidden bug-report affordance (capability `diagnostic-logging`).
 *
 * Two halves are under test. The *hidden* half: an affordance that cannot be stumbled into, cannot be
 * reached by an accessibility traversal, and does not exist at all on a build with nothing to send.
 * The *required-description* half: a report cannot be sent without an account of the problem, and what
 * is sent is trimmed — the description titles the issue in the reporting channel, so whitespace must
 * never become a title. Both are only observable from a test like this one. Headless:
 * `:ui:screens:jvmTest` renders offscreen (no display needed).
 */
class DiagnosticDumpGestureTest {

    @get:Rule
    val rule = createComposeRule()

    private fun fixedCutoff() = CutoffFormatter(
        now = { Instant.parse("2026-07-06T12:00:00Z") },
        zone = TimeZone.UTC,
    )

    /** The app-name nav label — rendered uppercase by the layout, in every state. */
    private val navLabel = "SNAPSYNC"

    /** The sheet's title — its presence is how "the sheet is open" is observed. */
    private val sheetTitle = "Report a problem"

    private val placeholder = "What went wrong, and what were you doing?"

    @Test
    fun `double-tapping the app name opens the bug-report sheet`() {
        rule.setContent {
            StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff(), onSendDiagnostics = { _, _ -> })
        }

        rule.onNodeWithText(sheetTitle).assertDoesNotExist()
        rule.onNodeWithText(navLabel).performTouchInput { doubleClick() }

        rule.onNodeWithText(sheetTitle).assertExists()
    }

    @Test
    fun `sending is refused until something is written`() {
        // The description is required, and the refusal is a disabled action rather than an error
        // message: an invalid submit is unreachable, so nothing needs to explain it.
        var sent = 0
        rule.setContent {
            StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff(), onSendDiagnostics = { _, _ -> sent++ })
        }

        rule.onNodeWithText(navLabel).performTouchInput { doubleClick() }
        rule.onNodeWithText("Send").assertIsNotEnabled()
        rule.onNodeWithText("Send").performClick()

        assertEquals(0, sent, "an empty description must not be sendable")
        rule.onNodeWithText(sheetTitle).assertExists()
    }

    @Test
    fun `whitespace alone is not a description`() {
        var sent = 0
        rule.setContent {
            StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff(), onSendDiagnostics = { _, _ -> sent++ })
        }

        rule.onNodeWithText(navLabel).performTouchInput { doubleClick() }
        rule.onNodeWithText(placeholder).performTextInput("   ")

        rule.onNodeWithText("Send").assertIsNotEnabled()
        assertEquals(0, sent)
    }

    @Test
    fun `sending fires the command once with the trimmed description`() {
        val sent = mutableListOf<Pair<String, String>>()
        rule.setContent {
            StatusScreen(
                UiState.CreateEvent(),
                cutoff = fixedCutoff(),
                onSendDiagnostics = { note, screen -> sent += note to screen },
            )
        }

        rule.onNodeWithText(navLabel).performTouchInput { doubleClick() }
        rule.onNodeWithText(placeholder).performTextInput("  photos stopped arriving  ")
        rule.onNodeWithText("Send").performClick()

        assertEquals(
            listOf("photos stopped arriving" to "CreateEvent"),
            sent,
            "one report, trimmed, labelled with the surface it was written from",
        )
        rule.onNodeWithText(sheetTitle).assertDoesNotExist()
    }

    @Test
    fun `cancelling sends nothing`() {
        var sent = 0
        rule.setContent {
            StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff(), onSendDiagnostics = { _, _ -> sent++ })
        }

        rule.onNodeWithText(navLabel).performTouchInput { doubleClick() }
        rule.onNodeWithText(placeholder).performTextInput("never mind")
        rule.onNodeWithText("Cancel").performClick()

        assertEquals(0, sent)
        rule.onNodeWithText(sheetTitle).assertDoesNotExist()
    }

    @Test
    fun `a build with no reporting channel opens no sheet at all`() {
        // The command is null — every dev, sideload and simulator build. The gesture must be absent,
        // not inert: an affordance that exists and silently does nothing is the one outcome forbidden,
        // because it is indistinguishable from a report that failed to send.
        rule.setContent {
            StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff(), onSendDiagnostics = null)
        }

        rule.onNodeWithText(navLabel).performTouchInput { doubleClick() }

        rule.onNodeWithText(sheetTitle).assertDoesNotExist()
    }

    @Test
    fun `the label names the join phase, not just the screen`() {
        // A gate stuck on a failed load is a different report from one parked on Ready, and neither
        // distinction reaches a log line — the join phase is screen state.
        val sent = mutableListOf<String>()
        rule.setContent {
            StatusScreen(
                UiState.JoiningEvent("11111111-2222-4333-8444-555555555555", JoinPhase.LoadFailed),
                cutoff = fixedCutoff(),
                onSendDiagnostics = { _, screen -> sent += screen },
            )
        }

        rule.onNodeWithText(navLabel).performTouchInput { doubleClick() }
        rule.onNodeWithText(placeholder).performTextInput("gate is stuck")
        rule.onNodeWithText("Send").performClick()

        assertEquals(listOf("JoiningEvent:LoadFailed"), sent)
    }

    @Test
    fun `the app-name label exposes no click action to accessibility`() {
        // The gesture is a raw pointer input on purpose. `combinedClickable` would publish an
        // OnClick semantics action and a ripple — which is exactly what makes a control read as a
        // control, and would put the hidden affordance into the accessibility tree.
        rule.setContent {
            StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff(), onSendDiagnostics = { _, _ -> })
        }

        rule.onNodeWithText(navLabel).assert(
            SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick),
        )
    }

    @Test
    fun `the affordance exists on the joined surface too`() {
        // A stuck sync is exactly when a report is wanted, and the label is the one element every state
        // renders — that is why the gesture lives on it.
        rule.setContent {
            StatusScreen(
                UiState.Joined(SyncHealth.InSync),
                cutoff = fixedCutoff(),
                onSendDiagnostics = { _, _ -> },
                eventName = "Anna's Birthday",
            )
        }

        rule.onNodeWithText(navLabel).performTouchInput { doubleClick() }

        rule.onNodeWithText(sheetTitle).assertExists()
    }
}
