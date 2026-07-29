package app.snapsync.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.SyncHealth
import app.snapsync.presentation.UiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import org.junit.Rule

/**
 * The hidden diagnostic-dump affordance (capability `diagnostic-logging`).
 *
 * Everything here is about the *hidden* half being real. A confirm dialog is easy; an affordance that
 * cannot be stumbled into, cannot be reached by an accessibility traversal, and does not exist at all
 * on a build with nothing to send — that is the contract, and it is only observable from a test like
 * this one. Headless: `:ui:screens:jvmTest` renders offscreen (no display needed).
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

    @Test
    fun `double-tapping the app name opens the confirmation`() {
        rule.setContent {
            StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff(), onSendDiagnostics = {})
        }

        rule.onNodeWithText("Send diagnostics?").assertDoesNotExist()
        rule.onNodeWithText(navLabel).performTouchInput { doubleClick() }

        rule.onNodeWithText("Send diagnostics?").assertExists()
    }

    @Test
    fun `confirming fires the command exactly once`() {
        var sent = 0
        rule.setContent {
            StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff(), onSendDiagnostics = { sent++ })
        }

        rule.onNodeWithText(navLabel).performTouchInput { doubleClick() }
        rule.onNodeWithText("Send").performClick()

        assertEquals(1, sent, "one confirmed gesture must send exactly one dump")
        rule.onNodeWithText("Send diagnostics?").assertDoesNotExist()
    }

    @Test
    fun `cancelling sends nothing`() {
        var sent = 0
        rule.setContent {
            StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff(), onSendDiagnostics = { sent++ })
        }

        rule.onNodeWithText(navLabel).performTouchInput { doubleClick() }
        rule.onNodeWithText("Cancel").performClick()

        assertEquals(0, sent)
        rule.onNodeWithText("Send diagnostics?").assertDoesNotExist()
    }

    @Test
    fun `a build with no reporting channel opens no dialog at all`() {
        // The command is null — every dev, sideload and simulator build. The gesture must be absent,
        // not inert: an affordance that exists and silently does nothing is the one outcome forbidden,
        // because it is indistinguishable from a dump that failed to send.
        rule.setContent {
            StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff(), onSendDiagnostics = null)
        }

        rule.onNodeWithText(navLabel).performTouchInput { doubleClick() }

        rule.onNodeWithText("Send diagnostics?").assertDoesNotExist()
    }

    @Test
    fun `the app-name label exposes no click action to accessibility`() {
        // The gesture is a raw pointer input on purpose. `combinedClickable` would publish an
        // OnClick semantics action and a ripple — which is exactly what makes a control read as a
        // control, and would put the hidden affordance into the accessibility tree.
        rule.setContent {
            StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff(), onSendDiagnostics = {})
        }

        rule.onNodeWithText(navLabel).assert(
            SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick),
        )
    }

    @Test
    fun `the affordance exists on the joined surface too`() {
        // A stuck sync is exactly when a dump is wanted, and the label is the one element every state
        // renders — that is why the gesture lives on it.
        rule.setContent {
            StatusScreen(
                UiState.Joined(SyncHealth.InSync),
                cutoff = fixedCutoff(),
                onSendDiagnostics = {},
                eventName = "Anna's Birthday",
            )
        }

        rule.onNodeWithText(navLabel).performTouchInput { doubleClick() }

        rule.onNodeWithText("Send diagnostics?").assertExists()
    }
}
