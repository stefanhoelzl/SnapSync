@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.ui

import app.snapsync.presentation.Layer
import app.snapsync.presentation.Overlays
import app.snapsync.presentation.PendingSwitch

import app.snapsync.model.eventEnd

import app.snapsync.model.eventStart

import app.snapsync.model.captureCeiling

import app.snapsync.model.captureCutoff

import app.snapsync.model.EventConfig

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.v2.runComposeUiTest
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

/**
 * The hidden bug-report affordance (capability `diagnostic-logging`).
 *
 * Two halves are under test. The *hidden* half: an affordance that cannot be stumbled into, cannot be
 * reached by an accessibility traversal, and does not exist at all on a build with nothing to send.
 * The *required-description* half: a report cannot be sent without an account of the problem, and what
 * is sent is trimmed — the description titles the issue in the reporting channel, so whitespace must
 * never become a title. Both are only observable from a test like this one. Headless on both targets:
 * `:ui:screens:jvmTest` renders offscreen (no display needed) and `:ui:screens:iosSimulatorArm64Test`
 * renders into the simulator's own offscreen scene.
 */
class DiagnosticDumpGestureTest {

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
    fun `double-tapping the app name asks for the bug-report sheet`() = runComposeUiTest {
        var opened = 0
        setContent {
            StatusScreen(
                UiState(Layer.CreateEvent()),
                cutoff = fixedCutoff(),
                actions = StatusActions(onSendDiagnostics = { _, _ -> }, surfaces = SurfaceActions(onReportBugOpen = { opened++ })),
            )
        }

        onNodeWithText(sheetTitle).assertDoesNotExist()
        onNodeWithText(navLabel).performTouchInput { doubleClick() }
        assertEquals(1, opened)
    }

    @Test
    fun `the sheet renders when the state says it is open`() = runComposeUiTest {
        setContent {
            StatusScreen(reporting(Layer.CreateEvent()), cutoff = fixedCutoff(), actions = diagnosticsActions())
        }
        onNodeWithText(sheetTitle).assertExists()
    }

    @Test
    fun `sending is refused until something is written`() = runComposeUiTest {
        // The description is required, and the refusal is a disabled action rather than an error
        // message: an invalid submit is unreachable, so nothing needs to explain it.
        var sent = 0
        setContent {
            StatusScreen(
                reporting(Layer.CreateEvent()), cutoff = fixedCutoff(), actions = StatusActions(onSendDiagnostics = { _, _ -> sent++ }))
        }

        onNodeWithText("Send").assertIsNotEnabled()
        onNodeWithText("Send").performClick()

        assertEquals(0, sent, "an empty description must not be sendable")
        onNodeWithText(sheetTitle).assertExists()
    }

    @Test
    fun `whitespace alone is not a description`() = runComposeUiTest {
        var sent = 0
        setContent {
            StatusScreen(
                reporting(Layer.CreateEvent()), cutoff = fixedCutoff(), actions = StatusActions(onSendDiagnostics = { _, _ -> sent++ }))
        }

        onNodeWithText(placeholder).performTextInput("   ")

        onNodeWithText("Send").assertIsNotEnabled()
        assertEquals(0, sent)
    }

    @Test
    fun `sending fires the command once with the trimmed description`() = runComposeUiTest {
        val sent = mutableListOf<Pair<String, String>>()
        var dismissed = 0
        setContent {
            StatusScreen(
                reporting(Layer.CreateEvent()),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    onSendDiagnostics = { note, screen -> sent += note to screen },
                    surfaces = SurfaceActions(onReportBugDismiss = { dismissed++ }),
                )
            )
        }

        onNodeWithText(placeholder).performTextInput("  photos stopped arriving  ")
        onNodeWithText("Send").performClick()

        assertEquals(
            listOf("photos stopped arriving" to "CreateEvent"),
            sent,
            "one report, trimmed, labelled with the surface it was written from",
        )
        assertEquals(1, dismissed, "sending also asks for the sheet to close")
    }

    @Test
    fun `cancelling sends nothing`() = runComposeUiTest {
        var sent = 0
        var dismissed = 0
        setContent {
            StatusScreen(
                reporting(Layer.CreateEvent()),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    onSendDiagnostics = { _, _ -> sent++ },
                    surfaces = SurfaceActions(onReportBugDismiss = { dismissed++ }),
                ),
            )
        }

        onNodeWithText(placeholder).performTextInput("never mind")
        onNodeWithText("Cancel").performClick()

        assertEquals(0, sent)
        assertEquals(1, dismissed)
    }

    @Test
    fun `a build with no reporting channel opens no sheet at all`() = runComposeUiTest {
        // The command is null — every dev, sideload and simulator build. The gesture must be absent,
        // not inert: an affordance that exists and silently does nothing is the one outcome forbidden,
        // because it is indistinguishable from a report that failed to send.
        setContent {
            StatusScreen(
                UiState(Layer.CreateEvent()), cutoff = fixedCutoff(), actions = StatusActions(onSendDiagnostics = null))
        }


        onNodeWithText(sheetTitle).assertDoesNotExist()
    }

    @Test
    fun `the label names the join phase and not just the screen`() = runComposeUiTest {
        // A gate stuck on a failed load is a different report from one parked on Ready, and neither
        // distinction reaches a log line — the join phase is screen state.
        val sent = mutableListOf<String>()
        setContent {
            StatusScreen(
                reporting(Layer.JoiningEvent("11111111-2222-4333-8444-555555555555", JoinPhase.LoadFailed)),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    onSendDiagnostics = { _, screen -> sent += screen },
                )
            )
        }

        onNodeWithText(placeholder).performTextInput("gate is stuck")
        onNodeWithText("Send").performClick()

        assertEquals(listOf("JoiningEvent:LoadFailed"), sent)
    }

    @Test
    fun `the app-name label exposes no click action to accessibility`() = runComposeUiTest {
        // The gesture is a raw pointer input on purpose. `combinedClickable` would publish an
        // OnClick semantics action and a ripple — which is exactly what makes a control read as a
        // control, and would put the hidden affordance into the accessibility tree.
        setContent {
            StatusScreen(
                UiState(Layer.CreateEvent()), cutoff = fixedCutoff(), actions = StatusActions(onSendDiagnostics = { _, _ -> }))
        }

        onNodeWithText(navLabel).assert(
            SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick),
        )
    }

    @Test
    fun `the affordance exists on the joined surface too`() = runComposeUiTest {
        // A stuck sync is exactly when a report is wanted, and the label is the one element every state
        // renders — that is why the gesture lives on it.
        setContent {
            StatusScreen(
                reportingOver(joinedWith(SyncHealth.InSync)),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    onSendDiagnostics = { _, _ -> },
                )
            )
        }


        onNodeWithText(sheetTitle).assertExists()
    }
}

// The membership and invite URL live inside the joined state now (capability `sync-status-screen`), so
// these tests build the state that carries them instead of passing them beside it.
private val SWITCH_MEMBERSHIP = EventConfig(
    eventId = "E1",
    name = "Anna's Birthday",
    minPhotoDate = captureCutoff("2026-07-06T12:00:00Z"),
    startsAt = eventStart("2026-07-06T12:00:00Z"),
    endsAt = eventEnd("2026-07-10T12:00:00Z"),
    maxPhotoDate = captureCeiling("2026-07-10T12:00:00Z"),
)

private fun joinedWith(health: SyncHealth, pendingSwitch: PendingSwitch? = null, name: String = "Anna's Birthday") =
    UiState(
        Layer.Joined(
            membership = SWITCH_MEMBERSHIP.copy(name = name),
            inviteUrl = "https://snapsync.stho.net/join#v=3&d=eyJldmVudElkIjoiRTEifQ",
            health = health,
            pendingSwitch = pendingSwitch,
        ),
    )

/** The same screen with the diagnostic sheet already open — the state a double-tap produces. */
private fun reporting(layer: Layer) = UiState(layer, Overlays(reportingBug = true))

/** The same, for a state a helper already built. */
private fun reportingOver(state: UiState) = state.copy(overlays = Overlays(reportingBug = true))

/** Diagnostics wired to a no-op, for the tests that only care that the sheet renders. */
private fun diagnosticsActions() = StatusActions(onSendDiagnostics = { _, _ -> })
