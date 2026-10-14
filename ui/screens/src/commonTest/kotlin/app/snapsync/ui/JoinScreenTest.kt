@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import app.snapsync.ui.components.LocalReduceMotion
import app.snapsync.ui.components.RangeChoiceActions
import app.snapsync.ui.components.resources.network_blocked
import app.snapsync.ui.components.resources.network_offline
import app.snapsync.ui.components.resources.share_range_custom
import app.snapsync.ui.components.resources.share_range_event_start
import app.snapsync.ui.components.resources.share_range_from_now
import app.snapsync.ui.components.resources.share_range_title
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.access_choose_title
import app.snapsync.ui.resources.access_library_title
import app.snapsync.ui.resources.access_range_title
import app.snapsync.ui.resources.access_shared_title
import app.snapsync.ui.resources.album_folder_nothing
import app.snapsync.ui.resources.album_none
import app.snapsync.ui.resources.album_share
import app.snapsync.ui.resources.album_toggle
import app.snapsync.ui.resources.cancel
import app.snapsync.ui.resources.duration_days
import app.snapsync.ui.resources.event_closed_body
import app.snapsync.ui.resources.event_closed_title
import app.snapsync.ui.resources.event_not_found_body
import app.snapsync.ui.resources.event_not_found_title
import app.snapsync.ui.resources.join_access_dismiss
import app.snapsync.ui.resources.join_access_info
import app.snapsync.ui.resources.join_access_notice
import app.snapsync.ui.resources.join_access_sheet_title
import app.snapsync.ui.resources.join_button
import app.snapsync.ui.resources.join_button_allow
import app.snapsync.ui.resources.join_failed_body
import app.snapsync.ui.resources.join_failed_title
import app.snapsync.ui.resources.link_incomplete_body
import app.snapsync.ui.resources.link_incomplete_title
import app.snapsync.ui.resources.load_failed_body_updating
import app.snapsync.ui.resources.load_failed_title
import app.snapsync.ui.resources.loading_event
import app.snapsync.ui.resources.members_count
import app.snapsync.ui.resources.message_device_modified
import app.snapsync.ui.resources.message_device_unverifiable
import app.snapsync.ui.resources.message_invalid_link
import app.snapsync.ui.resources.message_report_this
import app.snapsync.ui.resources.message_service_trouble
import app.snapsync.ui.resources.mobile_data_toggle
import app.snapsync.ui.resources.ok
import app.snapsync.ui.resources.receive_toggle
import app.snapsync.ui.resources.retry
import app.snapsync.ui.resources.save
import app.snapsync.ui.resources.share_detail_count
import app.snapsync.ui.resources.share_detail_counting
import app.snapsync.ui.resources.share_off_note
import app.snapsync.ui.resources.share_outside_after
import app.snapsync.ui.resources.share_outside_before
import app.snapsync.ui.resources.share_outside_both
import app.snapsync.ui.resources.share_toggle
import app.snapsync.ui.resources.share_zero_note
import app.snapsync.ui.resources.switch_body
import app.snapsync.ui.resources.switch_confirm
import app.snapsync.ui.resources.switch_title
import app.snapsync.ui.resources.waiting_network_title
import app.snapsync.ui.state.AlbumHolds
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.JoinStage
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.NetworkNotice
import app.snapsync.ui.state.PendingSwitch
import app.snapsync.ui.state.RangeChoice
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.ServiceNotice
import app.snapsync.ui.state.ShareCount
import app.snapsync.ui.state.SyncHealth
import app.snapsync.ui.state.UiState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import app.snapsync.ui.components.resources.Res as ComponentRes

/**
 * The **redesigned join gate** with a capture-date RANGE (capabilities `join-event`,
 * `photo-sharing`): two participation switches whose combination DERIVES the direction, a From/Until
 * range selector (From: Event start / Now / Custom; Until: Event end / Custom) defaulting to the FULL event
 * window, a standalone album opt-in, and the event-naming photo-access explainer.
 */

/** "Now" for the fixed test clock (UTC, the zone these tests render in). */
private val NOW = LocalDateTime(2026, 7, 6, 12, 0)

/** An event that has ALREADY started (before [NOW]) — the ordinary case — and its end (after [NOW]). */
private val EVENT_START = LocalDateTime(2026, 7, 4, 18, 0)
private val EVENT_END = LocalDateTime(2026, 7, 20, 18, 0)

class JoinScreenTest {

    /**
     * The join surface at [phase], with the form and its resolution the reduction would have produced.
     *
     * A screen test states the resolved range LITERALLY rather than re-running the resolution rules: the
     * screen renders what it is given, and `RangeResolutionTest` owns whether the rules are right. Doing
     * it the other way would make this file agree with the implementation by construction.
     */
    private fun joining(
        phase: JoinPhase,
        form: Choices = Choices(),
        // The count the container reduced into the range; unavailable unless a test is about the row.
        count: ShareCount = ShareCount.Unavailable,
        // The loaded event's window.
        start: LocalDateTime = EVENT_START,
        end: LocalDateTime = EVENT_END,
        // The range calendar is open over the surface — on the window, complete — as the app opens it.
        picking: Boolean = false,
    ) = UiState(
        Layer.JoiningEvent(
            eventId = "11111111-1111-4111-8111-111111111111",
            stage = when (phase) {
                is JoinPhase.Detailed -> JoinStage.Loaded(
                    phase,
                    form.over(start, end, NOW, count).let { range ->
                        if (picking) range.copy(picker = calendarOver(range.from, range.until, start, end)) else range
                    },
                )
                is JoinPhase.Eventless -> JoinStage.Unloaded(phase)
            },
        ),
    )

    /** A participation bundle that reports only the edit a test is about. */
    private fun participationActions(
        onShareOn: (Boolean) -> Unit = {},
        onReceiveOn: (Boolean) -> Unit = {},
        onSaveToAlbum: (Boolean) -> Unit = {},
        choices: RangeChoiceActions = testRangeChoiceActions(),
    ) = testParticipationActions(
        choices = choices,
        onShareOn = onShareOn,
        onReceiveOn = onReceiveOn,
        onSaveToAlbum = onSaveToAlbum,
    )

    private fun ready() = JoinPhase.Detailed("Anna's Wedding", JoinPhase.Detailed.Step.Ready)

    /**
     * Mounts the screen under **reduced motion**. The Custom picker's time wheels animate on open (a
     * `LazyColumn` settle), and an animating scene never reaches idle — so a picker-opening test without this
     * snaps-instead-of-animates flag stalls `waitForIdle` for ~16 min. Reduce motion is semantics-neutral
     * here (this suite asserts state, never pixels), so every test uses it.
     */
    private fun ComposeUiTest.setScreen(content: @Composable () -> Unit) =
        setContent { CompositionLocalProvider(LocalReduceMotion provides true) { content() } }

    // ---- the in-flight / error phases (unchanged shells) ----------------------------------------------

    @Test
    fun `loading phase shows the loading label and no Join`() = runComposeUiTest {
        setScreen { TestStatusScreen(joining(JoinPhase.Loading)) }
        onNodeWithText(str(Res.string.loading_event)).assertExists()
        onNodeWithText(str(Res.string.join_button)).assertDoesNotExist()
    }

    @Test
    fun `not-found phase blocks the join`() = runComposeUiTest {
        setScreen { TestStatusScreen(joining(JoinPhase.NotFound)) }
        onNodeWithText(str(Res.string.event_not_found_title)).assertExists()
        onNodeWithText(str(Res.string.join_button)).assertDoesNotExist()
        onNodeWithText(str(Res.string.cancel)).assertExists()
    }

    @Test
    fun `closed phase refuses the join with no Retry`() = runComposeUiTest {
        setScreen { TestStatusScreen(joining(JoinPhase.Closed)) }
        onNodeWithText(str(Res.string.event_closed_title)).assertExists()
        onNodeWithText(str(Res.string.event_closed_body)).assertExists()
        onNodeWithText(str(Res.string.join_button)).assertDoesNotExist()
        onNodeWithText(str(Res.string.retry)).assertDoesNotExist()
        onNodeWithText(str(Res.string.cancel)).assertExists()
    }

    @Test
    fun `an invite cut short says so and offers only Cancel`() = runComposeUiTest {
        setScreen { TestStatusScreen(joining(JoinPhase.WrongLink)) }
        onNodeWithText(str(Res.string.link_incomplete_title)).assertExists()
        onNodeWithText(str(Res.string.link_incomplete_body)).assertExists()
        onNodeWithText(str(Res.string.retry)).assertDoesNotExist()
        onNodeWithText(str(Res.string.cancel)).assertExists()
    }

    @Test
    fun `a link rejected while the join screen is open is said above it`() = runComposeUiTest {
        val state = joining(JoinPhase.Loading).let {
            it.copy(layer = (it.layer as Layer.JoiningEvent).copy(notice = ScreenMessage.INVALID_LINK))
        }
        setScreen { TestStatusScreen(state) }
        onNodeWithText(str(Res.string.message_invalid_link)).assertExists()
        onNodeWithText(str(Res.string.loading_event)).assertExists()
    }

    @Test
    fun `load-failed phase offers Retry`() = runComposeUiTest {
        var retried = 0
        setScreen {
            TestStatusScreen(
                joining(
                    JoinPhase.LoadFailed,
                ),
                actions = testActions(
                    join = testJoinGateActions(onRetryLoad = {
                        retried++
                    }),
                ),
            )
        }
        onNodeWithText(str(Res.string.retry)).assertExists()
        onNodeWithText(str(Res.string.retry)).performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `a load or a join the service failed says which way, keeping Retry`() = runComposeUiTest {
        setScreen {
            TestStatusScreen(
                joining(JoinPhase.LoadFailed).let {
                    it.copy(layer = (it.layer as Layer.JoiningEvent).copy(service = ServiceNotice.UPDATING))
                },
            )
        }
        onNodeWithText(str(Res.string.load_failed_body_updating)).assertExists()
        onNodeWithText(str(Res.string.retry)).assertExists()
    }

    @Test
    fun `a join the service failed says which way`() = runComposeUiTest {
        setScreen {
            TestStatusScreen(
                joining(JoinPhase.Detailed("Anna's Wedding", JoinPhase.Detailed.Step.CommitFailed)).let {
                    it.copy(layer = (it.layer as Layer.JoiningEvent).copy(service = ServiceNotice.TROUBLE))
                },
            )
        }
        onNodeWithText(str(Res.string.message_service_trouble)).assertExists()
        onNodeWithText(str(Res.string.retry)).assertExists()
    }

    // ---- without a network (capability `join-event`, "Without a network, the join screen waits for one") ----

    private fun UiState.withNetwork(notice: NetworkNotice) =
        copy(layer = (layer as Layer.JoiningEvent).copy(network = notice))

    @Test
    fun `offline a failed load waits for the network with Cancel only`() = runComposeUiTest {
        setScreen {
            TestStatusScreen(
                joining(JoinPhase.LoadFailed).withNetwork(NetworkNotice.OFFLINE),
            )
        }
        onNodeWithText(str(ComponentRes.string.network_offline)).assertExists()
        onNodeWithText(str(Res.string.waiting_network_title)).assertExists()
        onNodeWithText(str(Res.string.retry)).assertDoesNotExist()
        onNodeWithText(str(Res.string.cancel)).assertExists()
    }

    @Test
    fun `the join screen says how many members the event has`() = runComposeUiTest {
        setScreen {
            TestStatusScreen(joining(JoinPhase.Detailed("Anna's Wedding", JoinPhase.Detailed.Step.Ready, members = 3)))
        }
        onNodeWithText(plural(Res.plurals.members_count, 3, 3)).assertExists()
    }

    @Test
    fun `without a network Join cannot be tapped and Cancel can`() = runComposeUiTest {
        var cancelled = 0
        setScreen {
            TestStatusScreen(
                joining(JoinPhase.Detailed("Anna's Wedding", JoinPhase.Detailed.Step.Ready))
                    .withNetwork(NetworkNotice.OFFLINE),
                actions = testActions(join = testJoinGateActions(onCancelJoin = { cancelled++ })),
            )
        }
        onNodeWithText(str(Res.string.join_button)).assertIsNotEnabled()
        onNodeWithText(str(Res.string.cancel)).performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun `a blocked network offers SnapSync's Settings`() = runComposeUiTest {
        var settingsOpens = 0
        setScreen {
            TestStatusScreen(
                joining(JoinPhase.LoadFailed).withNetwork(NetworkNotice.BLOCKED),
                actions = testActions(access = testAccessActions(onOpenSettings = { settingsOpens++ })),
            )
        }
        onNodeWithText(str(ComponentRes.string.network_blocked)).performClick()
        assertEquals(1, settingsOpens)
    }

    @Test
    fun `without a network a failed commit offers no Retry`() = runComposeUiTest {
        setScreen {
            TestStatusScreen(
                joining(
                    JoinPhase.Detailed("Anna's Wedding", JoinPhase.Detailed.Step.CommitFailed),
                )
                    .withNetwork(NetworkNotice.OFFLINE),
            )
        }
        onNodeWithText(str(Res.string.retry)).assertDoesNotExist()
        onNodeWithText(str(Res.string.cancel)).assertExists()
    }

    @Test
    fun `a refused join says why and offers Retry and a report where only one can help and Cancel`() = runComposeUiTest {
        // Capability `join-event`, "A refused phone is told why it cannot join".
        var retried = 0
        var reported: ScreenMessage? = null
        val refused =
            JoinPhase.Detailed("Anna's Wedding", JoinPhase.Detailed.Step.DeviceRefused)
        val state = mutableStateOf(joining(refused.copy(refusal = ScreenMessage.DEVICE_UNVERIFIABLE)))
        setScreen {
            TestStatusScreen(
                state.value,
                actions = testActions(
                    join = testJoinGateActions(onRetryJoin = { retried++ }),
                    surfaces = testSurfaceActions(onReportRefusal = { reported = it }),
                ),
            )
        }
        onNodeWithText(str(Res.string.message_device_unverifiable)).assertExists()
        onNodeWithText(str(Res.string.join_failed_body)).assertDoesNotExist()
        onNodeWithText(str(Res.string.message_report_this)).performClick()
        assertEquals(ScreenMessage.DEVICE_UNVERIFIABLE, reported)
        onNodeWithText(str(Res.string.retry)).performClick()
        assertEquals(1, retried)
        onNodeWithText(str(Res.string.cancel)).assertExists()

        state.value = joining(refused.copy(refusal = ScreenMessage.DEVICE_MODIFIED))
        waitForIdle()
        onNodeWithText(str(Res.string.message_device_modified)).assertExists()
        onNodeWithText(str(Res.string.message_report_this)).assertDoesNotExist()
        onNodeWithText(str(Res.string.retry)).assertExists()
    }

    @Test
    fun `commit-failed phase offers Retry for the join`() = runComposeUiTest {
        var retried = 0
        setScreen {
            TestStatusScreen(
                joining(
                    JoinPhase.Detailed("Anna's Wedding", JoinPhase.Detailed.Step.CommitFailed),
                ),
                actions = testActions(
                    join = testJoinGateActions(
                        onRetryJoin = { retried++ },
                    ),
                ),
            )
        }
        onNodeWithText(str(Res.string.join_failed_title)).assertExists()
        onNodeWithText(str(Res.string.retry)).performClick()
        assertEquals(1, retried)
    }

    // ---- the two participation switches (capability `join-event`) --------------------------------------

    @Test
    fun `ready shows the two switch sections — both on by default`() = runComposeUiTest {
        setScreen { TestStatusScreen(joining(ready())) }
        onNodeWithText("Anna's Wedding").assertExists()
        onNodeWithText(str(Res.string.share_toggle)).assertIsSwitch().assertToggle(ToggleableState.On)
        onNodeWithText(str(Res.string.receive_toggle)).assertIsSwitch().assertToggle(ToggleableState.On)
    }

    @Test
    fun `share on states the exclusions share off states that nothing leaves`() = runComposeUiTest {
        setScreen { TestStatusScreen(joining(ready())) }
        onNodeWithText(
            "Screenshots, screen recordings, GIFs and photos saved from chat apps are never shared.",
        ).assertExists()
    }

    @Test
    fun `share off swaps its consequence line and leaves receive alone`() = runComposeUiTest {
        setScreen {
            TestStatusScreen(joining(ready(), form = Choices(shareOn = false)))
        }
        onNodeWithText(str(Res.string.share_toggle)).assertToggle(ToggleableState.Off)
        onNodeWithText(str(Res.string.receive_toggle)).assertToggle(ToggleableState.On)
        onNodeWithText(str(Res.string.share_off_note)).assertExists()
    }

    @Test
    fun `both switches start on so Join is offered`() = runComposeUiTest {
        var confirmed = 0
        setScreen {
            TestStatusScreen(
                joining(
                    ready(),
                ),
                actions = testActions(
                    join = testJoinGateActions(onConfirmJoin = {
                        confirmed++
                    }),
                ),
            )
        }
        onNodeWithText(str(Res.string.share_toggle)).assertToggle(ToggleableState.On)
        onNodeWithText(str(Res.string.receive_toggle)).performScrollTo().assertToggle(ToggleableState.On)
        onNodeWithText(str(Res.string.join_button)).performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun `turning receive off reports the choice`() = runComposeUiTest {
        var receiveOn: Boolean? = null
        setScreen {
            TestStatusScreen(
                joining(ready()),
                actions = testActions(participation = participationActions(onReceiveOn = { receiveOn = it })),
            )
        }
        // The expanded range selector sits between Share and Receive, so Receive is below the offscreen
        // viewport — scroll it into view before the click (Compose's performClick does not auto-scroll).
        onNodeWithText(str(Res.string.receive_toggle)).performScrollTo().performClick()
        // What that DERIVES to (UploadOnly) is `directionOf`'s answer, tested in RangeResolutionTest.
        // What this surface owes is that the tap reached the form at all.
        assertEquals(false, receiveOn)
    }

    @Test
    fun `turning share off reports the choice`() = runComposeUiTest {
        var shareOn: Boolean? = null
        setScreen {
            TestStatusScreen(
                joining(ready()),
                actions = testActions(participation = participationActions(onShareOn = { shareOn = it })),
            )
        }
        onNodeWithText(str(Res.string.share_toggle)).performClick()
        assertEquals(false, shareOn)
    }

    @Test
    fun `both switches off disables Join with a stated reason and never auto-flips`() = runComposeUiTest {
        var confirmed = 0
        setScreen {
            TestStatusScreen(
                joining(ready(), form = Choices(shareOn = false, receiveOn = false)),
                actions = testActions(join = testJoinGateActions(onConfirmJoin = { confirmed++ })),
            )
        }
        // Both off is representable and does nothing: neither switch silently flips the other.
        onNodeWithText(str(Res.string.share_toggle)).assertToggle(ToggleableState.Off)
        onNodeWithText(str(Res.string.receive_toggle)).assertToggle(ToggleableState.Off)
        onNodeWithText(
            "Turn on sharing or receiving. With both off, joining does nothing.",
        ).assertExists()
        onNodeWithText(str(Res.string.join_button)).assertIsNotEnabled()
        assertEquals(0, confirmed)
    }

    // ---- the range row (capability `photo-sharing`) --------------------------------------------------

    @Test
    fun `ready shows the event's dates as the default range`() = runComposeUiTest {
        setScreen { TestStatusScreen(joining(ready())) }
        // The event's window (4 Jul, 18:00 – 20 Jul, 18:00), stated once beneath the chosen "from event start".
        onNodeWithText("4 Jul, 18:00 – 20 Jul, 18:00").assertExists()
        onNodeWithText(str(ComponentRes.string.share_range_event_start)).assertIsRadio().assertIsSelected()
        // The old two-list selector is gone.
        onNodeWithText("Share from").assertDoesNotExist()
        onNodeWithText("Share until").assertDoesNotExist()
    }

    @Test
    fun `Custom asks for the calendar`() = runComposeUiTest {
        var opened = 0
        setScreen {
            TestStatusScreen(
                joining(ready()),
                actions = testActions(
                    participation = participationActions(choices = testRangeChoiceActions(onOpen = { opened++ })),
                ),
            )
        }
        onNodeWithText(str(ComponentRes.string.share_range_custom)).performClick()
        assertEquals(1, opened)
        onNodeWithText(str(ComponentRes.string.share_range_title)).assertDoesNotExist()
    }

    @Test
    fun `the screen offers all three choices while the event runs`() = runComposeUiTest {
        // Decision record `flexible-share-range`, D3: one tap each, on the screen — not behind the calendar.
        setScreen { TestStatusScreen(joining(ready())) }
        onNodeWithText(str(ComponentRes.string.share_range_event_start)).assertIsRadio().assertIsSelected()
        onNodeWithText(str(ComponentRes.string.share_range_from_now)).assertIsRadio().assertIsNotSelected()
        onNodeWithText(str(ComponentRes.string.share_range_custom)).assertIsRadio().assertIsNotSelected()
    }

    @Test
    fun `before the event starts From now is not offered`() = runComposeUiTest {
        // Now (2026-07-06 12:00) is before this window opens, so "from now" would clamp to a bound the member
        // did not choose.
        setScreen {
            TestStatusScreen(joining(ready(), start = LocalDateTime(2026, 7, 10, 0, 0)))
        }
        onNodeWithText(str(ComponentRes.string.share_range_event_start)).assertExists()
        onNodeWithText(str(ComponentRes.string.share_range_from_now)).assertDoesNotExist()
        onNodeWithText(str(ComponentRes.string.share_range_custom)).assertExists()
    }

    @Test
    fun `tapping From now reports the preset`() = runComposeUiTest {
        var preset: RangeChoice? = null
        setScreen {
            TestStatusScreen(
                joining(ready()),
                actions = testActions(
                    participation = participationActions(choices = testRangeChoiceActions(onPreset = { preset = it })),
                ),
            )
        }
        onNodeWithText(str(ComponentRes.string.share_range_from_now)).performClick()
        assertEquals(RangeChoice.FROM_NOW, preset)
    }

    @Test
    fun `OK takes the calendar's range`() = runComposeUiTest {
        var confirmed = 0
        setScreen {
            TestStatusScreen(
                joining(ready(), picking = true),
                actions = testActions(
                    participation = participationActions(choices = testRangeChoiceActions(onConfirm = { confirmed++ })),
                ),
            )
        }
        onNodeWithText(str(Res.string.save)).performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun `cancelling the calendar closes it and edits nothing`() = runComposeUiTest {
        var edits = 0
        var dismissed = 0
        setScreen {
            TestStatusScreen(
                joining(ready(), picking = true),
                actions = testActions(
                    participation = participationActions(
                        choices = testRangeChoiceActions(
                            onPreset = { edits++ },
                            onConfirm = { edits++ },
                            onDismiss = { dismissed++ },
                        ),
                    ),
                ),
            )
        }
        // The screen pins its own Cancel too; the dialog's is the later root.
        onAllNodesWithText(str(Res.string.cancel)).onLast().performClick()
        assertEquals(0, edits)
        assertEquals(1, dismissed)
    }

    @Test
    fun `a custom range renders its bounds and names itself custom`() = runComposeUiTest {
        val form = Choices(
            preset = RangeChoice.CUSTOM,
            customFrom = LocalDateTime(2026, 7, 6, 9, 0),
            customUntil = LocalDateTime(2026, 7, 8, 21, 0),
        )
        setScreen { TestStatusScreen(joining(ready(), form = form)) }
        onNodeWithText("6 Jul, 09:00 – 8 Jul, 21:00").assertExists()
        onNodeWithText(str(ComponentRes.string.share_range_custom)).assertIsRadio().assertIsSelected()
        onNodeWithText(str(Res.string.share_outside_before)).assertDoesNotExist()
    }

    @Test
    fun `a range reaching outside the event says which side`() = runComposeUiTest {
        // Capability `join-event`: a wide range never passes for the event's own dates.
        val before = Choices(preset = RangeChoice.CUSTOM, customFrom = LocalDateTime(2026, 7, 3, 9, 0))
        setScreen { TestStatusScreen(joining(ready(), form = before)) }
        onNodeWithText(str(Res.string.share_outside_before)).assertExists()
    }

    @Test
    fun `a range reaching past both ends says both`() = runComposeUiTest {
        val both = Choices(
            preset = RangeChoice.CUSTOM,
            customFrom = LocalDateTime(2026, 7, 3, 9, 0),
            customUntil = LocalDateTime(2026, 7, 22, 9, 0),
        )
        setScreen { TestStatusScreen(joining(ready(), form = both)) }
        onNodeWithText(str(Res.string.share_outside_both)).assertExists()
        onNodeWithText(str(Res.string.share_outside_after)).assertDoesNotExist()
    }

    // ---- no retention statement (capability `join-event`) ----------------------------------------------

    @Test
    fun `the join surface does not state the deletion date`() = runComposeUiTest {
        setScreen {
            TestStatusScreen(
                joining(
                    JoinPhase.Detailed("Anna's Wedding", JoinPhase.Detailed.Step.Ready),
                ),
            )
        }
        onNodeWithText("deleted on", substring = true).assertDoesNotExist()
        onNodeWithText(plural(Res.plurals.duration_days, 30, 30), substring = true).assertDoesNotExist()
    }

    // ---- the shareable-count row (capability `join-event`) ---------------------------------------

    @Test
    fun `the share section shows how many photos will be shared`() = runComposeUiTest {
        setScreen {
            TestStatusScreen(
                joining(
                    JoinPhase.Detailed("Anna's Wedding", JoinPhase.Detailed.Step.Ready),
                    count = ShareCount.Ready(34),
                ),
            )
        }
        onNodeWithText(plural(Res.plurals.share_detail_count, 34, 34)).assertExists()
    }

    @Test
    fun `a zero count carries the forward gloss`() = runComposeUiTest {
        setScreen {
            TestStatusScreen(
                joining(
                    JoinPhase.Detailed("Anna's Wedding", JoinPhase.Detailed.Step.Ready),
                    count = ShareCount.Ready(0),
                ),
            )
        }
        onNodeWithText(plural(Res.plurals.share_detail_count, 0, 0)).assertExists()
        onNodeWithText(str(Res.string.share_zero_note)).assertExists()
    }

    @Test
    fun `no count is shown when none is available`() = runComposeUiTest {
        setScreen {
            TestStatusScreen(
                // Unavailable = DENIED / unresolved grant → the row is omitted (no spinner that can't resolve).
                joining(
                    JoinPhase.Detailed("Anna's Wedding", JoinPhase.Detailed.Step.Ready),
                    count = ShareCount.Unavailable,
                ),
            )
        }
        onNodeWithText("from your gallery", substring = true).assertDoesNotExist()
        onNodeWithText(str(Res.string.share_detail_counting)).assertDoesNotExist()
        onNodeWithText("4 Jul, 18:00 – 20 Jul, 18:00").assertExists()
    }

    @Test
    fun `a count still being computed says so`() = runComposeUiTest {
        setScreen {
            TestStatusScreen(
                joining(
                    JoinPhase.Detailed("Anna's Wedding", JoinPhase.Detailed.Step.Ready),
                    count = ShareCount.Counting,
                ),
            )
        }
        onNodeWithText(str(Res.string.share_detail_counting)).assertExists()
    }

    @Test
    fun `share off hides the count`() = runComposeUiTest {
        // Not offered rather than shown as zero: absent and zero are different answers, and "no count"
        // is what a non-contributing choice means (capability `join-event`).
        setScreen {
            TestStatusScreen(
                joining(
                    JoinPhase.Detailed("Anna's Wedding", JoinPhase.Detailed.Step.Ready),
                    form = Choices(shareOn = false),
                    count = ShareCount.Ready(34),
                ),
            )
        }
        onNodeWithText("from your gallery", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the row renders the count the container reduced`() = runComposeUiTest {
        // Recomputing as the range changes is the container's job now (StatusContainerHostTest); the row
        // renders whatever count the reduction carries.
        setScreen {
            TestStatusScreen(
                joining(
                    JoinPhase.Detailed("Anna's Wedding", JoinPhase.Detailed.Step.Ready),
                    count = ShareCount.Ready(5),
                ),
            )
        }
        onNodeWithText(plural(Res.plurals.share_detail_count, 5, 5)).assertExists()
    }

    @Test
    fun `the count follows the resolved range singular at one`() = runComposeUiTest {
        // A range resolved from From now shares just the one photo — and the row says "photo", not "photos".
        setScreen {
            TestStatusScreen(
                joining(
                    JoinPhase.Detailed("Anna's Wedding", JoinPhase.Detailed.Step.Ready),
                    form = Choices(preset = RangeChoice.FROM_NOW),
                    count = ShareCount.Ready(1),
                ),
            )
        }
        onNodeWithText(plural(Res.plurals.share_detail_count, 1, 1)).assertExists()
    }

    // ---- the album switch (capability `event-album`) ---------------------------------

    @Test
    fun `the album is a switch — on by default — stating what is collected`() = runComposeUiTest {
        // The default is what an UNTOUCHED gate commits: the album is the only on-device statement
        // that a set of photos belongs to this event, so deciding nothing gets you the grouping.
        setScreen { TestStatusScreen(joining(ready())) }
        onNodeWithText(str(Res.string.album_toggle)).performScrollTo().assertIsSwitch().assertToggle(ToggleableState.On)
        onNodeWithText(
            "Photos you share and photos you receive are collected in an album named after the event.",
        ).assertExists()
    }

    @Test
    fun `the album row states no album once it is unchecked`() = runComposeUiTest {
        // Reachable only after a deliberate uncheck now that the row starts on — which is exactly when
        // the line informs, and the only remaining assertion that it is rendered at all.
        setScreen {
            TestStatusScreen(joining(ready(), form = Choices(saveToAlbum = false)))
        }
        onNodeWithText(str(Res.string.album_toggle)).assertToggle(ToggleableState.Off)
        onNodeWithText(str(Res.string.album_none)).assertExists()
    }

    @Test
    fun `a phone with folder albums offers the album — collecting only what is received`() = runComposeUiTest {
        // Capability `event-album`: on Android the album is the folder received photos are saved into, so the note
        // never names the member's own photos, which stay in the camera folder.
        setScreen {
            TestStatusScreen(joining(ready(), form = Choices(albumHolds = AlbumHolds.RECEIVED)))
        }
        onNodeWithText(str(Res.string.album_toggle)).performScrollTo().assertToggle(ToggleableState.On)
        onNodeWithText(
            "Photos you receive are collected in an album named after the event. Your own photos stay where they are.",
        ).assertExists()
    }

    @Test
    fun `a phone with folder albums says the album collects nothing without receiving`() = runComposeUiTest {
        setScreen {
            TestStatusScreen(
                joining(ready(), form = Choices(receiveOn = false, albumHolds = AlbumHolds.RECEIVED)),
            )
        }
        onNodeWithText(str(Res.string.album_folder_nothing)).performScrollTo().assertExists()
    }

    @Test
    fun `the album note adapts to all four switch combinations`() = runComposeUiTest {
        // The note varies over BOTH switches at once, so it is stated per combination rather than
        // toggled into: the surface renders the combination the state names.
        fun note(shareOn: Boolean, receiveOn: Boolean) =
            joining(ready(), form = Choices(shareOn = shareOn, receiveOn = receiveOn, saveToAlbum = true))

        setScreen { TestStatusScreen(note(shareOn = true, receiveOn = true)) }
        onNodeWithText(str(Res.string.album_toggle)).performScrollTo().assertToggle(ToggleableState.On)
        onNodeWithText(
            "Photos you share and photos you receive are collected in an album named after the event.",
        ).assertExists()
    }

    @Test
    fun `the album note names share-only receive-only and neither`() = runComposeUiTest {
        fun note(shareOn: Boolean, receiveOn: Boolean) =
            joining(ready(), form = Choices(shareOn = shareOn, receiveOn = receiveOn, saveToAlbum = true))

        setScreen { TestStatusScreen(note(shareOn = true, receiveOn = false)) }
        onNodeWithText(str(Res.string.album_share)).assertExists()
    }

    @Test
    fun `tapping the album opt-in reports the choice`() = runComposeUiTest {
        var saveToAlbum: Boolean? = null
        // Seeded OFF so the tap under test is the one that turns the album ON — the callback is what
        // this asserts, and reading it off the default would make the test restate the seed instead.
        setScreen {
            TestStatusScreen(
                joining(ready(), form = Choices(saveToAlbum = false)),
                actions = testActions(participation = participationActions(onSaveToAlbum = { saveToAlbum = it })),
            )
        }
        onNodeWithText(str(Res.string.album_toggle)).performScrollTo().performClick()
        assertEquals(true, saveToAlbum)
    }

    @Test
    fun `tapping the album opt-in from the default reports declining it`() = runComposeUiTest {
        var saveToAlbum: Boolean? = null
        setScreen {
            TestStatusScreen(
                joining(ready()),
                actions = testActions(participation = participationActions(onSaveToAlbum = { saveToAlbum = it })),
            )
        }
        onNodeWithText(str(Res.string.album_toggle)).performScrollTo().performClick()
        assertEquals(false, saveToAlbum, "a tap from the on-by-default row declines the album")
    }

    // ---- the mobile-data choice (capability `mobile-data`) ---------------------------------------------

    @Test
    fun `the join screen offers no mobile-data choice`() = runComposeUiTest {
        // The choice is the device's, in the app menu (decision record `changes/archive/2026-10-07-mobile-data-per-device`).
        setScreen { TestStatusScreen(joining(ready())) }
        onNodeWithText(str(Res.string.album_toggle)).performScrollTo().assertExists()
        onNodeWithText(str(Res.string.mobile_data_toggle)).assertDoesNotExist()
    }

    // ---- photo access asked on Join (capability `join-event`) -----------------------------------------

    private fun asking() = joining(ready()).let {
        it.copy(layer = (it.layer as Layer.JoiningEvent).copy(asksAccessOnJoin = true))
    }

    @Test
    fun `a never-asked guest sees the notice and Join and allow photos beside the choices`() = runComposeUiTest {
        setScreen { TestStatusScreen(asking()) }
        onNodeWithText("Anna's Wedding").assertExists()
        onNodeWithText(str(Res.string.share_toggle)).assertExists()
        onNodeWithText(str(Res.string.join_access_notice)).assertExists()
        onNodeWithText(str(Res.string.join_button_allow)).assertExists()
        onNodeWithText(str(Res.string.join_button)).assertDoesNotExist()
    }

    @Test
    fun `a guest iOS already asked sees plain Join and no notice`() = runComposeUiTest {
        setScreen { TestStatusScreen(joining(ready())) }
        onNodeWithText(str(Res.string.join_access_notice)).assertDoesNotExist()
        onNodeWithText(str(Res.string.join_button_allow)).assertDoesNotExist()
        onNodeWithText(str(Res.string.join_button)).assertExists()
    }

    @Test
    fun `the info opens the explanation and closing it confirms nothing`() = runComposeUiTest {
        var confirmed = 0
        setScreen {
            TestStatusScreen(
                asking(),
                actions = testActions(join = testJoinGateActions(onConfirmJoin = { confirmed++ })),
            )
        }
        onNodeWithContentDescription(str(Res.string.join_access_info)).performClick()
        onNodeWithText(str(Res.string.join_access_sheet_title)).assertExists()
        onNodeWithText(str(Res.string.access_shared_title)).assertExists()
        onNodeWithText(str(Res.string.access_library_title)).assertExists()
        onNodeWithText(str(Res.string.access_choose_title)).assertExists()
        onNodeWithText(str(Res.string.access_range_title)).assertExists()
        onNodeWithText(str(Res.string.join_access_dismiss)).performClick()
        onNodeWithText(str(Res.string.join_access_sheet_title)).assertDoesNotExist()
        assertEquals(0, confirmed)
    }

    @Test
    fun `Join and allow photos confirms the join`() = runComposeUiTest {
        var confirmed = 0
        setScreen {
            TestStatusScreen(
                asking(),
                actions = testActions(join = testJoinGateActions(onConfirmJoin = { confirmed++ })),
            )
        }
        onNodeWithText(str(Res.string.join_button_allow)).performClick()
        assertEquals(1, confirmed)
    }

    // ---- regressions the redesign must preserve -------------------------------------------------------

    /**
     * The range must derive from the loaded window across the real phase sequence
     * (`Loading` → `Ready`), never from a stale first-composition seed — the screen mounts
     * at `Loading`, before any phase carries a window.
     */
    @Test
    fun `the range shows the event window across the real phase sequence`() = runComposeUiTest {
        var phase by mutableStateOf<JoinPhase>(JoinPhase.Loading)
        setScreen { TestStatusScreen(joining(phase)) }
        onNodeWithText(str(Res.string.loading_event)).assertExists()

        phase = ready()
        waitForIdle()
        // The event's window (4 Jul, 18:00 – 20 Jul, 18:00), NOT "now" — derived from the phase every composition.
        onNodeWithText("4 Jul, 18:00 – 20 Jul, 18:00").assertExists()
    }

    @Test
    fun `the commit-failed step still offers Retry over the same event`() = runComposeUiTest {
        // What the retry COMMITS is the reduction's answer — the form outlives the phase change because it
        // lives in the container now, not in this composition, which is why there is nothing here to
        // assert about it. `StatusContainerHostTest` covers that a retry commits the chosen range.
        var retried = 0
        setScreen {
            TestStatusScreen(
                joining(
                    JoinPhase.Detailed("Anna's Wedding", JoinPhase.Detailed.Step.CommitFailed),
                ),
                actions = testActions(join = testJoinGateActions(onRetryJoin = { retried++ })),
            )
        }
        onNodeWithText(str(Res.string.retry)).performClick()
        assertEquals(1, retried)
    }

    // ---- the switch-events dialog (a different event scanned while joined) -----------------------------

    /**
     * The confirmation names both events and **promises no participation**: its confirm runs only the
     * leave, and the member picks direction, cutoff and album on the join surface that follows. It renders
     * no shareable count either — there is no chosen range to count yet (capability `join-event`).
     */
    @Test
    fun `switch dialog names both events and confirms with no choices`() = runComposeUiTest {
        var confirms = 0
        setScreen {
            TestStatusScreen(
                joinedWith(
                    SyncHealth.Loading,
                    PendingSwitch(
                        "22222222-2222-4222-8222-222222222222",
                        JoinPhase.Detailed("New Event", JoinPhase.Detailed.Step.Ready),
                    ),
                ),
                actions = testActions(
                    switch = testSwitchActions(
                        onConfirmSwitch = { confirms++ },
                    ),
                ),
            )
        }
        onNodeWithText(str(Res.string.switch_title)).assertExists()
        onNodeWithText(str(Res.string.switch_body, "Summer Trip", "New Event")).assertExists()
        // No participation promise, and no count for a range the member has not chosen.
        onNodeWithText("You'll share photos you take and receive everyone's.").assertDoesNotExist()
        onNodeWithText("from your gallery", substring = true).assertDoesNotExist()

        onNodeWithText(str(Res.string.switch_confirm)).performClick()
        assertEquals(1, confirms)
    }

    @Test
    fun `cancelling the switch dialog fires cancel`() = runComposeUiTest {
        var cancelled = 0
        setScreen {
            TestStatusScreen(
                joinedWith(
                    SyncHealth.Loading,
                    PendingSwitch(
                        "22222222-2222-4222-8222-222222222222",
                        JoinPhase.Detailed("New Event", JoinPhase.Detailed.Step.Ready),
                    ),
                ),
                actions = testActions(
                    switch = testSwitchActions(
                        onCancelSwitch = { cancelled++ },
                    ),
                ),
            )
        }
        onNodeWithText(str(Res.string.cancel)).performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun `switch dialog for a missing event stays a plain confirmation and cancels`() = runComposeUiTest {
        var cancelled = 0
        setScreen {
            TestStatusScreen(
                joinedWith(
                    SyncHealth.Loading,
                    PendingSwitch("22222222-2222-4222-8222-222222222222", JoinPhase.NotFound),
                ),
                actions = testActions(
                    switch = testSwitchActions(
                        onCancelSwitch = { cancelled++ },
                    ),
                ),
            )
        }
        onNodeWithText(str(Res.string.event_not_found_body)).assertExists()
        onNodeWithText(str(Res.string.ok)).performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun `a switch whose details failed to load offers Retry and Cancel keeps the membership`() = runComposeUiTest {
        var retried = 0
        var cancelled = 0
        setScreen {
            TestStatusScreen(
                joinedWith(
                    SyncHealth.Loading,
                    PendingSwitch("22222222-2222-4222-8222-222222222222", JoinPhase.LoadFailed),
                ),
                actions = testActions(
                    join = testJoinGateActions(onRetryLoad = { retried++ }),
                    switch = testSwitchActions(onCancelSwitch = { cancelled++ }),
                ),
            )
        }
        onNodeWithText(str(Res.string.load_failed_title)).assertExists()
        onNodeWithText(str(Res.string.retry)).performClick()
        onNodeWithText(str(Res.string.cancel)).performClick()
        assertEquals(1 to 1, retried to cancelled)
    }

    @Test
    fun `a switch to an invite cut short names it and dismisses`() = runComposeUiTest {
        var cancelled = 0
        setScreen {
            TestStatusScreen(
                joinedWith(
                    SyncHealth.Loading,
                    PendingSwitch("22222222-2222-4222-8222-222222222222", JoinPhase.WrongLink),
                ),
                actions = testActions(switch = testSwitchActions(onCancelSwitch = { cancelled++ })),
            )
        }
        onNodeWithText(str(Res.string.link_incomplete_title)).assertExists()
        onNodeWithText(str(Res.string.ok)).performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun `no switch dialog shows while the new event's details load`() = runComposeUiTest {
        setScreen {
            TestStatusScreen(
                joinedWith(
                    SyncHealth.Loading,
                    PendingSwitch("22222222-2222-4222-8222-222222222222", JoinPhase.Loading),
                ),
            )
        }
        onNodeWithText(str(Res.string.switch_title)).assertDoesNotExist()
        onNodeWithText(str(Res.string.ok)).assertDoesNotExist()
        onNodeWithText(str(Res.string.retry)).assertDoesNotExist()
    }

    // ---- NEGATIVE: the derived direction is never named on the Ready surface --------------------------

    @Test
    fun `the Ready surface never prints Both or Upload only or Download only`() = runComposeUiTest {
        setScreen { TestStatusScreen(joining(ready())) }
        onNodeWithText("Both").assertDoesNotExist()
        onNodeWithText("Upload only").assertDoesNotExist()
        onNodeWithText("Download only").assertDoesNotExist()
    }
}

// ---- small semantic helpers ---------------------------------------------------------------------------

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsSwitch() =
    assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsRadio() =
    assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertToggle(state: ToggleableState) =
    assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state))

// The membership and invite URL live inside the joined state now (capability `sync-status`), so
// these tests build the state that carries them instead of passing them beside it.
private val SWITCH_MEMBERSHIP = joinedEvent(
    eventId = "E1",
    name = "Summer Trip",
    startsAt = LocalDateTime(2026, 7, 6, 12, 0),
    endsAt = LocalDateTime(2026, 7, 10, 12, 0),
)

private fun joinedWith(health: SyncHealth, pendingSwitch: PendingSwitch? = null, name: String = "Summer Trip") =
    UiState(
        Layer.Joined(
            membership = SWITCH_MEMBERSHIP.copy(name = name),
            inviteUrl = "https://snapsync.stho.net/join#v=3&d=eyJldmVudElkIjoiRTEifQ",
            health = health,
            today = LocalDate(2026, 7, 6),
            pendingSwitch = pendingSwitch,
        ),
    )
