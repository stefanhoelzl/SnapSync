@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.feature.creation.readmodel.CreationFailureReason
import app.snapsync.feature.creation.readmodel.CreationStatus
import app.snapsync.model.EventConfig
import app.snapsync.model.ForegroundReturn
import app.snapsync.model.NetworkAccess
import app.snapsync.model.TimestampText
import app.snapsync.presentation.onIntent
import app.snapsync.testids.testEventId
import app.snapsync.ui.components.LocalReduceMotion
import app.snapsync.ui.components.resources.calendar_next_month
import app.snapsync.ui.components.resources.network_blocked
import app.snapsync.ui.components.resources.network_offline
import app.snapsync.ui.components.resources.range_end_pick_time
import app.snapsync.ui.components.resources.status_in_sync
import app.snapsync.ui.components.resources.status_sync_pending
import app.snapsync.ui.components.resources.wheel_end_hour
import app.snapsync.ui.components.resources.wheel_end_minute
import app.snapsync.ui.components.resources.wheel_start_hour
import app.snapsync.ui.components.resources.wheel_start_minute
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.create_button
import app.snapsync.ui.resources.create_join_hint
import app.snapsync.ui.resources.create_lasts
import app.snapsync.ui.resources.create_name_placeholder
import app.snapsync.ui.resources.create_step_end_time
import app.snapsync.ui.resources.create_step_name
import app.snapsync.ui.resources.create_title
import app.snapsync.ui.resources.duration_days
import app.snapsync.ui.resources.duration_hours
import app.snapsync.ui.resources.leave_event
import app.snapsync.ui.resources.message_create_dates_refused
import app.snapsync.ui.resources.message_create_failed
import app.snapsync.ui.resources.message_create_name_refused
import app.snapsync.ui.resources.message_device_unverifiable
import app.snapsync.ui.resources.message_invalid_link
import app.snapsync.ui.resources.message_report_this
import app.snapsync.ui.resources.rename_event
import app.snapsync.ui.resources.share_invite
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.ScreenMessage
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import app.snapsync.ui.components.resources.Res as ComponentRes

/**
 * The create form (capability `create-event`), the real screen over the real container: the form is the app's — what
 * the host enters crosses as intents, and what the screen shows is what the container made of it — so each test taps
 * the screen and asserts what it shows, or what the container fired. The clock starts on Monday 6 July 2026, 12:00 UTC.
 */
class CreateFlowTest {

    @Test
    fun `create screen shows the name input and the scan-to-join hint`() = createTest { _ ->
        onNodeWithText(str(Res.string.create_title)).assertExists()
        onNodeWithText(str(Res.string.create_join_hint)).assertExists()
        onNodeWithText(str(Res.string.create_name_placeholder)).assertExists()
        onNodeWithText(str(Res.string.create_button)).assertExists()
    }

    @Test
    fun `invalid deeplink error shows below Create in place of the scan hint`() = createTest { rig ->
        rig.host.onOpenUrl("not an invite")
        awaitState(rig) { it.create?.error == ScreenMessage.INVALID_LINK }
        onNodeWithText(str(Res.string.message_invalid_link)).assertExists()
        onNodeWithText(str(Res.string.create_join_hint)).assertDoesNotExist()
    }

    @Test
    fun `a create failure shows below Create in place of the scan hint`() =
        createTest(ContainerRig(creation = failed(CreationFailureReason.SERVER))) { _ ->
            onNodeWithText(str(Res.string.message_create_failed)).assertExists()
            onNodeWithText(str(Res.string.create_join_hint)).assertDoesNotExist()
        }

    @Test
    fun `a refused create says which part the service refused`() {
        val creation = failed(CreationFailureReason.INVALID_NAME)
        createTest(ContainerRig(creation = creation)) { rig ->
            onNodeWithText(str(Res.string.message_create_name_refused)).assertExists()
            creation.value = CreationStatus.Failed(CreationFailureReason.INVALID_WINDOW)
            awaitState(rig) { it.create?.error == ScreenMessage.CREATE_DATES_REFUSED }
            onNodeWithText(str(Res.string.message_create_dates_refused)).assertExists()
        }
    }

    @Test
    fun `the start is preset to now and the last day to today with its time blank`() = createTest { _ ->
        onNodeWithText("6 Jul 2026, 12:00").assertExists()
        onNodeWithText(str(ComponentRes.string.range_end_pick_time, "6 Jul 2026")).assertExists()
        onNodeWithContentDescription(str(ComponentRes.string.wheel_end_hour), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "not set"))
    }

    @Test
    fun `a refused phone is offered the report in the line below Create`() =
        createTest(ContainerRig(deviceRefusal = DeviceRefusal.DEVICE_UNVERIFIABLE)) { rig ->
            val told = "${str(Res.string.message_device_unverifiable)} ${str(Res.string.message_report_this)}"
            onNodeWithText(told).performClick()
            awaitState(rig) { it.overlays.reportingBug }
        }

    @Test
    fun `the line above Create names the next missing step`() = createTest { rig ->
        onNodeWithText(str(Res.string.create_step_name)).assertExists()
        onNodeWithText(str(Res.string.create_button)).assertIsNotEnabled()

        typeName(rig, "My Party")
        onNodeWithText(str(Res.string.create_step_end_time)).assertExists()
        onNodeWithText(str(Res.string.create_button)).assertIsNotEnabled()

        // Tapping the last day alone does not complete the range.
        pickDay(rig, "Wednesday, 8 July 2026", day = 8)
        onNodeWithText(str(Res.string.create_step_end_time)).assertExists()
        onNodeWithText(str(Res.string.create_button)).assertIsNotEnabled()

        setUntilHour(rig, "13")
        onNodeWithText(str(Res.string.create_lasts, plural(Res.plurals.duration_days, 2, 2))).assertExists()
        onNodeWithText(str(Res.string.create_button)).assertIsEnabled()
    }

    @Test
    fun `tapping create submits the typed name AND the chosen range`() = createTest { rig ->
        completeForm(rig, "My Party")
        onNodeWithText(str(Res.string.create_button)).performClick()
        awaitFired(rig, "create:My Party")
        assertEquals(
            Triple(
                "My Party",
                EventStart(TimestampText.parse("2026-07-06T12:00:00Z")),
                EventEnd(TimestampText.parse("2026-07-08T13:00:00Z")),
            ),
            rig.created.single(),
        )
    }

    @Test
    fun `a same-day event needs only the end time`() = createTest { rig ->
        typeName(rig, "Dinner")
        setUntilHour(rig, "13")
        onNodeWithText(str(Res.string.create_lasts, plural(Res.plurals.duration_hours, 1, 1))).assertExists()
        onNodeWithText(str(Res.string.create_button)).performClick()
        awaitFired(rig, "create:Dinner")
        assertEquals(
            Triple(
                "Dinner",
                EventStart(TimestampText.parse("2026-07-06T12:00:00Z")),
                EventEnd(TimestampText.parse("2026-07-06T13:00:00Z")),
            ),
            rig.created.single(),
        )
    }

    @Test
    fun `a choice in the range freezes the start and it is not re-derived at submit`() = createTest { rig ->
        // The summary is the screen's statement about what will be sent. A start that silently drifted after the host
        // chose their range would make the screen lie.
        completeForm(rig, "My Party")
        rig.clock.instant = Instant.parse("2026-07-06T12:10:00Z")
        rig.foreground.value = ForegroundReturn(1, awayFor = 1.minutes)
        onNodeWithText(str(Res.string.create_button)).performClick()
        awaitFired(rig, "create:My Party")
        onNodeWithText("6 Jul 2026, 12:00").assertExists()
        assertEquals(EventStart(TimestampText.parse("2026-07-06T12:00:00Z")), rig.created.single().second)
    }

    @Test
    fun `a return to the foreground moves an untouched start to now and keeps the name`() = createTest { rig ->
        typeName(rig, "My Party")
        rig.clock.instant = Instant.parse("2026-07-06T12:07:00Z")
        rig.foreground.value = ForegroundReturn(1, awayFor = 1.minutes)
        awaitState(rig) { it.create?.form?.picker?.from?.minute == 7 }
        onNodeWithText("6 Jul 2026, 12:07").assertExists()
        assertEquals("My Party", nameShown())
    }

    @Test
    fun `a short absence keeps a chosen range and a long one starts a fresh draft`() = createTest { rig ->
        // Capability `create-event`, "A long absence starts a fresh draft".
        completeForm(rig, "My Party")

        rig.clock.instant = Instant.parse("2026-07-06T12:10:00Z")
        rig.foreground.value = ForegroundReturn(1, awayFor = 10.minutes)
        waitForIdle()
        onNodeWithText("6 Jul 2026, 12:00").assertExists()
        onNodeWithText("8 Jul 2026, 13:00").assertExists()

        rig.clock.instant = Instant.parse("2026-07-06T12:40:00Z")
        rig.foreground.value = ForegroundReturn(2, awayFor = 30.minutes)
        awaitState(rig) { it.create?.form?.draft == 1 }
        assertEquals("", nameShown())
        onNodeWithText("6 Jul 2026, 12:40").assertExists()
        onNodeWithText(str(ComponentRes.string.range_end_pick_time, "6 Jul 2026")).assertExists()
    }

    @Test
    fun `a failed create brings back the name and range the host entered and Create retries with them`() {
        // Capability `create-event`, "A failed create keeps what the host entered". The form gives way to the in-flight
        // screen while the create runs; the clock moves meanwhile, so a draft rebuilt from scratch would show a
        // DIFFERENT start and no end.
        val creation = kotlinx.coroutines.flow.MutableStateFlow<CreationStatus>(CreationStatus.Idle)
        createTest(ContainerRig(creation = creation)) { rig ->
            completeForm(rig, "My Party")
            onNodeWithText(str(Res.string.create_button)).performClick()
            awaitFired(rig, "create:My Party")

            creation.value = CreationStatus.InFlight
            awaitState(rig) { it.layer == Layer.CreatingEvent }
            // Still before the range's end (8 July 13:00), so the retry is not asked how long others may join.
            rig.clock.instant = Instant.parse("2026-07-08T08:30:00Z")
            creation.value = CreationStatus.Failed(CreationFailureReason.SERVER)
            awaitState(rig) { it.create?.error == ScreenMessage.CREATE_FAILED }

            onNodeWithText(str(Res.string.message_create_failed)).assertExists()
            assertEquals("My Party", nameShown())
            onNodeWithText("6 Jul 2026, 12:00").assertExists()
            onNodeWithText("8 Jul 2026, 13:00").assertExists()
            onNodeWithText(str(Res.string.create_button)).assertIsEnabled().performClick()
            waitUntil(timeoutMillis = 5_000) { rig.created.size == 2 }
            assertEquals(rig.created[0], rig.created[1], "the retry submits exactly what the failed attempt did")
        }
    }

    @Test
    fun `leaving the create flow drops the draft so a later visit starts afresh`() = createTest { rig ->
        // The draft belongs to ONE visit: a host who created, joined and later left meets an empty name, a newly
        // frozen start and a blank end time.
        completeForm(rig, "My Party")

        rig.config.value = MEMBERSHIP
        awaitState(rig) { it.layer is Layer.Joined }
        rig.clock.instant = Instant.parse("2026-07-09T08:30:00Z")
        rig.config.value = null
        awaitState(rig) { it.create?.form?.picker?.from?.day == 9 }

        assertEquals("", nameShown())
        onNodeWithText("9 Jul 2026, 08:30").assertExists()
        onNodeWithText(str(ComponentRes.string.range_end_pick_time, "9 Jul 2026")).assertExists()
    }

    @Test
    fun `tapping the missing name puts the cursor in the name field`() = createTest { _ ->
        onNodeWithText(str(Res.string.create_step_name)).assertHasClickAction().performClick()
        waitForIdle()
        onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun `tapping the missing name also raises the keyboard`() {
        val keyboard = CountingKeyboard()
        createTest(keyboard = keyboard) { _ ->
            onNodeWithText(str(Res.string.create_step_name)).performClick()
            waitForIdle()
            assertTrue(keyboard.shown > 0, "the keyboard was not asked to show")
        }
    }

    @Test
    fun `without a keyboard to raise — tapping the missing name still puts the cursor in the field`() =
        createTest(keyboard = null) { _ ->
            onNodeWithText(str(Res.string.create_step_name)).performClick()
            waitForIdle()
            onNode(hasSetTextAction()).assertIsFocused()
        }

    @Test
    fun `tapping the missing end time brings the end wheels into view without setting a time`() =
        // A phone-sized viewport, so the wheels start below the fold as they do on a Samsung A-series.
        createTest(phoneSized = true) { rig ->
            typeName(rig, "My Party")
            onNodeWithContentDescription(str(ComponentRes.string.wheel_end_hour), useUnmergedTree = true)
                .assertIsNotDisplayed()

            onNodeWithText(str(Res.string.create_step_end_time)).assertHasClickAction().performClick()
            waitForIdle()
            onNodeWithContentDescription(str(ComponentRes.string.wheel_end_hour), useUnmergedTree = true)
                .assertIsDisplayed()
            onNodeWithText(str(ComponentRes.string.range_end_pick_time, "6 Jul 2026")).assertExists()
            onNodeWithText(str(Res.string.create_button)).assertIsNotEnabled()
        }

    @Test
    fun `once the end time is set the line above Create is not a button`() = createTest { rig ->
        completeForm(rig, "My Party")
        onNodeWithText(
            str(Res.string.create_lasts, plural(Res.plurals.duration_days, 2, 2)),
        ).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun `the preset start is cut to the minute it shows`() =
        // Seconds the host cannot see must not make the created start differ from the shown one.
        createTest(ContainerRig(clock = TestClock(Instant.parse("2026-07-06T12:00:37Z")))) { rig ->
            completeForm(rig, "My Party")
            onNodeWithText(str(Res.string.create_button)).performClick()
            awaitFired(rig, "create:My Party")
            assertEquals(EventStart(TimestampText.parse("2026-07-06T12:00:00Z")), rig.created.single().second)
        }

    @Test
    fun `all four time wheels are on the screen with the calendar`() = createTest { _ ->
        onNodeWithText("July 2026").assertExists()
        onNodeWithContentDescription(str(ComponentRes.string.wheel_start_hour), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "12"))
        onNodeWithContentDescription(str(ComponentRes.string.wheel_start_minute), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "00"))
        onNodeWithContentDescription(str(ComponentRes.string.wheel_end_hour), useUnmergedTree = true).assertExists()
        onNodeWithContentDescription(str(ComponentRes.string.wheel_end_minute), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the name field caps at 100 characters`() = createTest { _ ->
        val field = onNode(hasSetTextAction())
        field.performTextInput("a".repeat(100))
        field.performTextInput("b")
        assertEquals(100, nameShown().length)
    }

    @Test
    fun `create layer shows no sync line and no leave and no invite`() = createTest { _ ->
        onNodeWithText(str(ComponentRes.string.status_in_sync)).assertDoesNotExist()
        onNodeWithText(str(ComponentRes.string.status_sync_pending)).assertDoesNotExist()
        onNodeWithText(str(Res.string.leave_event)).assertDoesNotExist()
        onNodeWithText(str(Res.string.share_invite)).assertDoesNotExist()
    }

    @Test
    fun `the rename pen is absent on the create screen — there is no heading to rename`() = createTest { _ ->
        onNodeWithContentDescription(str(Res.string.rename_event)).assertDoesNotExist()
    }

    @Test
    fun `the create screen states the longest range an event can have`() = createTest { _ ->
        onNodeWithText("An event can last up to 30 days", substring = true).assertExists()
    }

    @Test
    fun `the last day cannot be picked past 30 days from the start`() = createTest { _ ->
        // Capability `create-event`, "A range longer than 30 days cannot be chosen".
        onNodeWithContentDescription(str(ComponentRes.string.calendar_next_month)).performClick()
        onNodeWithContentDescription("Wednesday, 5 August 2026").assertIsEnabled() // 30 days on
        onNodeWithContentDescription("Thursday, 6 August 2026").assertIsNotEnabled() // 31 days on
    }

    @Test
    fun `a tap that reaches Create while it is disabled creates nothing`() = createTest { rig ->
        typeName(rig, "Party")
        // No end yet, so Create is disabled — yet its action stays in the tree, where an accessibility service can
        // fire it.
        onNodeWithText(
            str(Res.string.create_button),
        ).assertIsNotEnabled().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(emptyList(), rig.created)
    }

    @Test
    fun `offline Create cannot be tapped and the line below it says so`() =
        createTest(
            ContainerRig(network = NetworkAccess.Offline, creation = failed(CreationFailureReason.SERVER)),
        ) { rig ->
            completeForm(rig, "Party")
            onNodeWithText(str(ComponentRes.string.network_offline)).assertExists()
            onNodeWithText(str(Res.string.message_create_failed)).assertDoesNotExist()
            onNodeWithText(str(Res.string.create_button)).assertIsNotEnabled()
        }

    @Test
    fun `blocked the create screen offers SnapSync’s Settings`() =
        createTest(ContainerRig(network = NetworkAccess.Blocked)) { rig ->
            onNodeWithText(str(ComponentRes.string.network_blocked)).performClick()
            awaitFired(rig, "openSettings")
        }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

    private fun failed(reason: CreationFailureReason) =
        kotlinx.coroutines.flow.MutableStateFlow<CreationStatus>(CreationStatus.Failed(reason))

    /** The name field's text as shown. */
    private fun ComposeUiTest.nameShown(): String =
        onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** The create screen over [rig], with the wheels snapping instantly so the scene can idle. */
    private fun createTest(
        rig: ContainerRig = ContainerRig(),
        keyboard: SoftwareKeyboardController? = KEEP,
        phoneSized: Boolean = false,
        block: ComposeUiTest.(ContainerRig) -> Unit,
    ) = runComposeUiTest {
        try {
            setContent {
                val state by rig.host.screen.collectAsState()
                val screen = @androidx.compose.runtime.Composable {
                    CompositionLocalProvider(LocalReduceMotion provides true) {
                        WithoutKeyboardInsets {
                            StatusScreen(
                                state = state,
                                dateFormats = testDates,
                                actions = statusActions(rig.host::onIntent),
                            )
                        }
                    }
                }
                val sized = @androidx.compose.runtime.Composable {
                    if (phoneSized) Box(Modifier.size(390.dp, 640.dp)) { screen() } else screen()
                }
                if (keyboard === KEEP) {
                    sized()
                } else {
                    CompositionLocalProvider(
                        LocalSoftwareKeyboardController provides keyboard,
                    ) { sized() }
                }
            }
            block(rig)
        } finally {
            rig.scope.cancel()
        }
    }

    private companion object {
        /** No keyboard override: the test platform's own. */
        val KEEP: SoftwareKeyboardController = CountingKeyboard()

        val MEMBERSHIP = EventConfig(
            eventId = testEventId("11111111-1111-4111-8111-111111111111"),
            name = "My Party",
            minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-06T12:00:00Z")),
            endsAt = EventEnd(TimestampText.parse("2026-07-08T13:00:00Z")),
            maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-08T13:00:00Z")),
            deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
        )
    }
}
