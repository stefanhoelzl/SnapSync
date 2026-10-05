@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.snapsync.model.AppLink
import app.snapsync.model.BuildLabel
import app.snapsync.model.EventDetails
import app.snapsync.model.JoinPhase
import app.snapsync.model.Layer
import app.snapsync.model.Overlays
import app.snapsync.model.ReportOutcome
import app.snapsync.model.UiState
import app.snapsync.model.deletesAt
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import app.snapsync.model.joinPhase
import app.snapsync.presentation.CutoffFormatter
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import app.snapsync.ui.resources.Res
import app.snapsync.ui.components.resources.Res as ComponentRes
import app.snapsync.ui.components.resources.menu
import app.snapsync.ui.resources.menu_privacy
import app.snapsync.ui.resources.menu_version
import app.snapsync.ui.resources.menu_website
import app.snapsync.ui.resources.report_problem
import app.snapsync.ui.resources.report_sent
import app.snapsync.ui.resources.report_saved
import app.snapsync.ui.resources.report_not_sent

/**
 * The app menu and the word on a sent report, as drawn (capabilities `sync-status`, `privacy-security`): the button
 * is a real control where the layer offers the menu and absent where it does not, the drawer shows what the state
 * says, each row asks for its own intent, the footer is not a control, and each report outcome reads as itself.
 */
class AppMenuScreenTest {

    private val cutoff = CutoffFormatter(now = { Instant.parse("2026-07-06T12:00:00Z") }, zone = TimeZone.UTC)

    private val details = EventDetails(
        "Anna's Birthday",
        eventStart("2026-07-06T00:00:00Z"),
        eventEnd("2026-07-13T00:00:00Z"),
        deletesAt("2026-08-05T00:00:00Z"),
    )

    @Test
    fun `the menu button asks for the menu`() = runComposeUiTest {
        var opened = 0
        setContent {
            TestStatusScreen(UiState(Layer.CreateEvent()), cutoff, testActions(menu = testMenuActions(onMenuOpen = { opened++ })))
        }
        onNodeWithText(str(Res.string.report_problem)).assertDoesNotExist()
        onNodeWithContentDescription(str(ComponentRes.string.menu)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `no menu button while a create or a join is in progress`() = runComposeUiTest {
        var state by androidx.compose.runtime.mutableStateOf(UiState(Layer.CreatingEvent))
        setContent { TestStatusScreen(state, cutoff) }
        onNodeWithContentDescription(str(ComponentRes.string.menu)).assertDoesNotExist()
        state = UiState(Layer.JoiningEvent(eventId = "E", phase = joinPhase(JoinPhase.Detailed.Step.Committing, details)))
        waitForIdle()
        onNodeWithContentDescription(str(ComponentRes.string.menu)).assertDoesNotExist()
        state = UiState(Layer.JoiningEvent(eventId = "E", phase = JoinPhase.LoadFailed))
        waitForIdle()
        onNodeWithContentDescription(str(ComponentRes.string.menu)).assertExists()
    }

    @Test
    fun `the open menu holds the report — the two links and the build — and each row asks for its own act`() =
        runComposeUiTest {
            val asked = mutableListOf<String>()
            setContent {
                TestStatusScreen(
                    UiState(Layer.CreateEvent(), Overlays(menuOpen = true), build = BuildLabel("0.12", "2140")),
                    cutoff,
                    testActions(
                        menu = testMenuActions(
                            onReportBug = { asked += "report" },
                            onOpenLink = { asked += it.name },
                        ),
                    ),
                )
            }
            waitForIdle()
            onNodeWithText(str(Res.string.report_problem)).performClick()
            onNodeWithText(str(Res.string.menu_website)).performClick()
            onNodeWithText(str(Res.string.menu_privacy)).performClick()
            assertEquals(listOf("report", AppLink.WEBSITE.name, AppLink.PRIVACY_POLICY.name), asked)
            val footer = onNodeWithText(str(Res.string.menu_version, "0.12", "2140")).fetchSemanticsNode()
            assertFalse(hasClickAction().matches(footer), "the build line is shown, never a control")
        }

    @Test
    fun `tapping outside the open menu asks to close it`() = runComposeUiTest {
        var dismissed = 0
        setContent {
            TestStatusScreen(
                UiState(Layer.CreateEvent(), Overlays(menuOpen = true)),
                cutoff,
                testActions(menu = testMenuActions(onMenuDismiss = { dismissed++ })),
            )
        }
        waitForIdle()
        onRoot().performTouchInput { click(Offset(width - 8f, height / 2f)) }
        waitForIdle()
        assertTrue(dismissed >= 1, "the scrim's tap is a dismissal the state must hear")
    }

    @Test
    fun `each report outcome reads as itself — and a tap puts it away`() = runComposeUiTest {
        var state by androidx.compose.runtime.mutableStateOf(UiState(Layer.CreateEvent()))
        var dismissed = 0
        setContent {
            TestStatusScreen(state, cutoff, testActions(menu = testMenuActions(onReportNoticeDismiss = { dismissed++ })))
        }
        for (outcome in ReportOutcome.entries) {
            state = UiState(Layer.CreateEvent(), Overlays(reportNotice = outcome))
            waitForIdle()
            onNode(hasText(str(noticeOf(outcome)))).assertExists()
        }
        onNodeWithText(str(Res.string.report_not_sent)).performClick()
        assertEquals(1, dismissed)
    }
}

private fun noticeOf(outcome: ReportOutcome) = when (outcome) {
    ReportOutcome.SENT -> Res.string.report_sent
    ReportOutcome.SAVED -> Res.string.report_saved
    ReportOutcome.NOT_SENT -> Res.string.report_not_sent
}
