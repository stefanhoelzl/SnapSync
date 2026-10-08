@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.snapsync.model.AppLink
import app.snapsync.model.BuildLabel
import app.snapsync.model.Direction
import app.snapsync.model.EventDetails
import app.snapsync.model.JoinPhase
import app.snapsync.model.JoinStage
import app.snapsync.model.Layer
import app.snapsync.model.MobileDataState
import app.snapsync.model.Overlays
import app.snapsync.model.ReportOutcome
import app.snapsync.model.ResolvedRange
import app.snapsync.model.UiState
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import app.snapsync.model.joinPhase
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.ui.components.resources.menu
import app.snapsync.ui.components.resources.menu_close
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.menu_privacy
import app.snapsync.ui.resources.menu_version
import app.snapsync.ui.resources.menu_website
import app.snapsync.ui.resources.mobile_data_not_saved
import app.snapsync.ui.resources.mobile_data_off_note
import app.snapsync.ui.resources.mobile_data_on_note
import app.snapsync.ui.resources.mobile_data_toggle
import app.snapsync.ui.resources.report_not_sent
import app.snapsync.ui.resources.report_problem
import app.snapsync.ui.resources.report_saved
import app.snapsync.ui.resources.report_sent
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import app.snapsync.ui.components.resources.Res as ComponentRes

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

    /** The range a committing join carries; the menu does not read it. */
    private val range = ResolvedRange(
        windowStart = LocalDateTime(2026, 7, 6, 0, 0),
        windowEnd = LocalDateTime(2026, 7, 13, 0, 0),
        from = LocalDateTime(2026, 7, 6, 0, 0),
        until = LocalDateTime(2026, 7, 13, 0, 0),
        chosenFrom = captureCutoff("2026-07-06T00:00:00Z"),
        chosenUntil = captureCeiling("2026-07-13T00:00:00Z"),
        direction = Direction.Both,
        commitEnabled = true,
        nowAvailable = true,
        today = LocalDate(2026, 7, 6),
    )

    @Test
    fun `the menu button asks for the menu`() = runComposeUiTest {
        var opened = 0
        setContent {
            TestStatusScreen(
                UiState(Layer.CreateEvent()),
                cutoff,
                testActions(menu = testMenuActions(onMenuOpen = { opened++ })),
            )
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
        state = UiState(
            Layer.JoiningEvent(eventId = "E", stage = JoinStage.Loaded(joinPhase(JoinPhase.Detailed.Step.Committing, details), range)),
        )
        waitForIdle()
        onNodeWithContentDescription(str(ComponentRes.string.menu)).assertDoesNotExist()
        state = UiState(Layer.JoiningEvent(eventId = "E", stage = JoinStage.Unloaded(JoinPhase.LoadFailed)))
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

    // ---- the device's mobile-data switch (capability `mobile-data`) ---------------------------------------

    @Test
    fun `the open menu leads with the mobile-data switch and says what it means and a tap asks to flip it`() =
        runComposeUiTest {
            val flips = mutableListOf<Boolean>()
            setContent {
                // No event: the switch is the device's, offered before any join.
                TestStatusScreen(
                    UiState(Layer.CreateEvent(), Overlays(menuOpen = true)),
                    cutoff,
                    testActions(menu = testMenuActions(onMobileData = { flips += it })),
                )
            }
            waitForIdle()
            onNodeWithText(str(Res.string.mobile_data_on_note)).assertExists()
            val switchTop = onNodeWithText(str(Res.string.mobile_data_toggle)).fetchSemanticsNode().positionInRoot.y
            val reportTop = onNodeWithText(str(Res.string.report_problem)).fetchSemanticsNode().positionInRoot.y
            assertTrue(switchTop < reportTop, "the switch comes first, ahead of the report")
            onNodeWithText(str(Res.string.mobile_data_toggle)).performClick()
            assertEquals(listOf(false), flips, "a tap from on asks to keep photos off mobile data")
        }

    @Test
    fun `with mobile data off the menu says photos travel only on Wi-Fi`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                UiState(Layer.CreateEvent(), Overlays(menuOpen = true), mobileData = MobileDataState(on = false)),
                cutoff,
            )
        }
        waitForIdle()
        onNodeWithText(str(Res.string.mobile_data_off_note)).assertExists()
    }

    @Test
    fun `a flip that could not be saved is said in the menu`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                UiState(
                    Layer.CreateEvent(),
                    Overlays(menuOpen = true),
                    mobileData = MobileDataState(on = true, notSaved = true),
                ),
                cutoff,
            )
        }
        waitForIdle()
        onNodeWithText(str(Res.string.mobile_data_not_saved)).assertExists()
        onNodeWithText(str(Res.string.mobile_data_on_note)).assertDoesNotExist()
    }

    @Test
    fun `tapping well beside the open menu on a phone-sized screen asks to close it`() = runComposeUiTest {
        var dismissed = 0
        setContent {
            // An iPhone SE's width: M3's own 360dp sheet would cover the tap below; the menu must leave it uncovered.
            Box(Modifier.size(375.dp, 667.dp)) {
                TestStatusScreen(
                    UiState(Layer.CreateEvent(), Overlays(menuOpen = true)),
                    cutoff,
                    testActions(menu = testMenuActions(onMenuDismiss = { dismissed++ })),
                )
            }
        }
        waitForIdle()
        onRoot().performTouchInput { click(Offset(340.dp.toPx(), 333.dp.toPx())) }
        waitForIdle()
        assertTrue(dismissed >= 1, "the scrim's tap is a dismissal the state must hear")
    }

    @Test
    fun `the menu's close button asks to close it`() = runComposeUiTest {
        var dismissed = 0
        setContent {
            TestStatusScreen(
                UiState(Layer.CreateEvent(), Overlays(menuOpen = true)),
                cutoff,
                testActions(menu = testMenuActions(onMenuDismiss = { dismissed++ })),
            )
        }
        waitForIdle()
        onNodeWithContentDescription(str(ComponentRes.string.menu_close)).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `each report outcome reads as itself — and a tap puts it away`() = runComposeUiTest {
        var state by androidx.compose.runtime.mutableStateOf(UiState(Layer.CreateEvent()))
        var dismissed = 0
        setContent {
            TestStatusScreen(
                state,
                cutoff,
                testActions(menu = testMenuActions(onReportNoticeDismiss = { dismissed++ })),
            )
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
