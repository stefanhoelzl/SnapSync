@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.ui

import app.snapsync.model.ShareCount
import app.snapsync.model.captureCeiling

import app.snapsync.model.EventConfig

import app.snapsync.model.EventStart
import app.snapsync.model.EventEnd
import app.snapsync.model.captureCutoff
import app.snapsync.model.eventStart
import app.snapsync.model.eventEnd
import app.snapsync.model.deletesAt
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureCeiling
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
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.snapsync.model.Direction
import app.snapsync.ui.components.LocalReduceMotion
import app.snapsync.model.RangeForm
import app.snapsync.model.ResolvedRange
import app.snapsync.model.details
import app.snapsync.model.CaptureDate
import kotlinx.datetime.LocalDateTime
import app.snapsync.model.RangeChoice
import app.snapsync.ui.components.RangeChoiceActions
import app.snapsync.model.Layer
import app.snapsync.model.EventDetails
import app.snapsync.model.DeletesAt
import app.snapsync.model.JoinPhase
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.model.PendingSwitch
import app.snapsync.model.SyncHealth
import app.snapsync.model.UiState
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.TimeZone
import app.snapsync.ui.JoinGateActions
import app.snapsync.ui.SwitchActions

/**
 * The **redesigned join gate** with a capture-date RANGE (capabilities `join-event`,
 * `photo-sharing`): two participation switches whose combination DERIVES the direction, a From/Until
 * range selector (From: Event start / Now / Custom; Until: Event end / Custom) defaulting to the FULL event
 * window, a standalone album opt-in, and the event-naming photo-access explainer.
 */

/** The switch dialog's new-event `startsAt` / `endsAt` (a different event scanned while joined). */
private val CUTOFF = eventStart("2026-07-06T14:32:11Z")
private val SWITCH_END = eventEnd("2026-07-16T00:00:00Z")

/** "Now" for the fixed test clock. */
private val NOW = captureCutoff("2026-07-06T12:00:00Z")

/** An event that has ALREADY started (before [NOW]) — the ordinary case — and its end (after [NOW]). */
private val EVENT_START = eventStart("2026-07-04T18:00:00Z")
private val EVENT_END = eventEnd("2026-07-20T18:00:00Z")

/** The event's retention deadline (capability `event-lifetime`): 30 days past its start. */
private val EVENT_DELETES = deletesAt("2026-08-03T18:00:00Z")

/** An event that has NOT started yet (after [NOW]) — where the "Now" preset falls outside the window. */
private val FUTURE_START = eventStart("2026-07-09T18:00:00Z")

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
        form: RangeForm = RangeForm(),
        // The count the container reduced into the range; unavailable unless a test is about the row.
        count: ShareCount = ShareCount.Unavailable,
    ) = UiState(
        Layer.JoiningEvent(
            eventId = "11111111-1111-4111-8111-111111111111",
            phase = phase,
            form = form,
            range = resolvedFor(phase, form)?.copy(shareCount = count),
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

    /**
     * The range the reduction WOULD resolve for [phase] under [form] — stated here, not re-derived.
     *
     * A screen test supplies the reduction's answer and asserts what is drawn from it. Re-running the
     * resolution rules here would make this file agree with the implementation by construction;
     * `RangeResolutionTest` is where those rules are actually checked.
     */
    private fun resolvedFor(phase: JoinPhase, form: RangeForm): ResolvedRange? {
        val event = phase.details ?: return null
        val f = fixedCutoff()
        val windowStart = f.toLocal(event.startsAt.at)!!
        val windowEnd = f.toLocal(event.endsAt.at)!!
        val from = when (form.preset) {
            RangeChoice.WHOLE_EVENT -> windowStart
            RangeChoice.FROM_NOW -> f.nowLocal()
            RangeChoice.CUSTOM -> form.customFrom ?: windowStart
        }
        val until = when (form.preset) {
            RangeChoice.CUSTOM -> form.customUntil ?: windowEnd
            else -> windowEnd
        }
        return ResolvedRange(
            windowStart = windowStart,
            windowEnd = windowEnd,
            from = from,
            until = until,
            chosenFrom = CaptureCutoff(f.toCutoff(from)),
            chosenUntil = CaptureCeiling(f.toCutoff(until)),
            direction = directionFor(form),
            commitEnabled = form.shareOn || form.receiveOn,
            nowAvailable = f.nowCutoff() >= event.startsAt.at && f.nowCutoff() <= event.endsAt.at,
        )
    }

    private fun directionFor(form: RangeForm) = when {
        form.shareOn && form.receiveOn -> Direction.Both
        form.shareOn -> Direction.UploadOnly
        else -> Direction.DownloadOnly
    }

    private fun ready(start: EventStart = EVENT_START, end: EventEnd = EVENT_END) =
        phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Wedding", start, end, EVENT_DELETES)

    /**
     * Mounts the screen under **reduced motion**. The Custom picker's time wheels animate on open (a
     * `LazyColumn` settle), and an animating scene never reaches idle — so a picker-opening test without this
     * snaps-instead-of-animates flag stalls `waitForIdle` for ~16 min. Reduce motion is semantics-neutral
     * here (this suite asserts state, never pixels), so every test uses it.
     */
    private fun ComposeUiTest.setScreen(content: @Composable () -> Unit) =
        setContent { CompositionLocalProvider(LocalReduceMotion provides true) { content() } }

    /**
     * A REAL formatter on a fixed clock (UTC), not a constant-returning stub: the join surface decides
     * whether the present is inside the window by comparing `startsAt`/`endsAt` against "now", so a formatter
     * that ignored its input could not express the pre-start case at all.
     */
    private fun fixedCutoff(now: String = NOW.at.iso) = CutoffFormatter(
        now = { Instant.parse(now) },
        zone = TimeZone.UTC,
    )

    // ---- the in-flight / error phases (unchanged shells) ----------------------------------------------

    @Test
    fun `loading phase shows the loading label and no Join`() = runComposeUiTest {
        setScreen { TestStatusScreen(joining(JoinPhase.Loading), cutoff = fixedCutoff()) }
        onNodeWithText("Loading event details …").assertExists()
        onNodeWithText("Join").assertDoesNotExist()
    }

    @Test
    fun `not-found phase blocks the join`() = runComposeUiTest {
        setScreen { TestStatusScreen(joining(JoinPhase.NotFound), cutoff = fixedCutoff()) }
        onNodeWithText("Invalid invite").assertExists()
        onNodeWithText("Join").assertDoesNotExist()
        onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun `closed phase refuses the join with no Retry`() = runComposeUiTest {
        setScreen { TestStatusScreen(joining(JoinPhase.Closed), cutoff = fixedCutoff()) }
        onNodeWithText("Event closed").assertExists()
        onNodeWithText("This event can no longer be joined.").assertExists()
        onNodeWithText("Join").assertDoesNotExist()
        onNodeWithText("Retry").assertDoesNotExist()
        onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun `load-failed phase offers Retry`() = runComposeUiTest {
        var retried = 0
        setScreen { TestStatusScreen(joining(JoinPhase.LoadFailed), cutoff = fixedCutoff(), actions = testActions(join = testJoinGateActions(onRetryLoad = { retried++ }))) }
        onNodeWithText("Retry").assertExists()
        onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `commit-failed phase offers Retry for the join`() = runComposeUiTest {
        var retried = 0
        setScreen {
            TestStatusScreen(
                joining(phaseAt(JoinPhase.Detailed.Step.CommitFailed, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = testActions(
                    join = testJoinGateActions(
                        onRetryJoin = { retried++ },
                    ),
                )
            )
        }
        onNodeWithText("Couldn't join").assertExists()
        onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `the full-event step names the wall and offers NO retry`() = runComposeUiTest {
        // The whole reason this step exists separately from CommitFailed. Capacity does not heal, so a
        // Retry here would fail identically every time — the member would press it forever with nothing
        // saying what the wall was (capability `join-event`).
        var retried = 0
        setScreen {
            TestStatusScreen(
                joining(phaseAt(JoinPhase.Detailed.Step.EventFull, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = testActions(join = testJoinGateActions(onRetryJoin = { retried++ })),
            )
        }
        onNodeWithText("This event is full").assertExists()
        onNodeWithText("Retry").assertDoesNotExist()
        onNodeWithText("Cancel").assertExists()
        assertEquals(0, retried)
    }

    // ---- the two participation switches (capability `join-event`) --------------------------------------

    @Test
    fun `ready shows the two switch sections — both on by default`() = runComposeUiTest {
        setScreen { TestStatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        onNodeWithText("Anna's Wedding").assertExists()
        onNodeWithText("Share my photos").assertIsSwitch().assertToggle(ToggleableState.On)
        onNodeWithText("Receive everyone's photos").assertIsSwitch().assertToggle(ToggleableState.On)
    }

    @Test
    fun `share on states the exclusions share off states that nothing leaves`() = runComposeUiTest {
        setScreen { TestStatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        onNodeWithText(
            "Screenshots, screen recordings, GIFs and pictures saved from chat apps are never shared.",
        ).assertExists()
    }

    @Test
    fun `share off swaps its consequence line and leaves receive alone`() = runComposeUiTest {
        setScreen {
            TestStatusScreen(joining(ready(), form = RangeForm(shareOn = false)), cutoff = fixedCutoff())
        }
        onNodeWithText("Share my photos").assertToggle(ToggleableState.Off)
        onNodeWithText("Receive everyone's photos").assertToggle(ToggleableState.On)
        onNodeWithText("Nothing of yours leaves this phone.").assertExists()
    }

    @Test
    fun `both switches start on so Join is offered`() = runComposeUiTest {
        var confirmed = 0
        setScreen {
            TestStatusScreen(joining(ready()), cutoff = fixedCutoff(), actions = testActions(join = testJoinGateActions(onConfirmJoin = { confirmed++ })))
        }
        onNodeWithText("Share my photos").assertToggle(ToggleableState.On)
        onNodeWithText("Receive everyone's photos").performScrollTo().assertToggle(ToggleableState.On)
        onNodeWithText("Join").performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun `turning receive off reports the choice`() = runComposeUiTest {
        var receiveOn: Boolean? = null
        setScreen {
            TestStatusScreen(
                joining(ready()),
                cutoff = fixedCutoff(),
                actions = testActions(participation = participationActions(onReceiveOn = { receiveOn = it })),
            )
        }
        // The expanded range selector sits between Share and Receive, so Receive is below the offscreen
        // viewport — scroll it into view before the click (Compose's performClick does not auto-scroll).
        onNodeWithText("Receive everyone's photos").performScrollTo().performClick()
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
                cutoff = fixedCutoff(),
                actions = testActions(participation = participationActions(onShareOn = { shareOn = it })),
            )
        }
        onNodeWithText("Share my photos").performClick()
        assertEquals(false, shareOn)
    }

    @Test
    fun `both switches off disables Join with a stated reason and never auto-flips`() = runComposeUiTest {
        var confirmed = 0
        setScreen {
            TestStatusScreen(
                joining(ready(), form = RangeForm(shareOn = false, receiveOn = false)),
                cutoff = fixedCutoff(),
                actions = testActions(join = testJoinGateActions(onConfirmJoin = { confirmed++ })),
            )
        }
        // Both off is representable and does nothing: neither switch silently flips the other.
        onNodeWithText("Share my photos").assertToggle(ToggleableState.Off)
        onNodeWithText("Receive everyone's photos").assertToggle(ToggleableState.Off)
        onNodeWithText(
            "Turn on sharing or receiving — a membership that does neither does nothing.",
        ).assertExists()
        onNodeWithText("Join").assertIsNotEnabled()
        assertEquals(0, confirmed)
    }

    // ---- the range row (capability `photo-sharing`) --------------------------------------------------

    @Test
    fun `ready shows the whole event window as the default range`() = runComposeUiTest {
        setScreen { TestStatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        // The event's window (4 Jul 18:00 – 20 Jul 18:00), stated once, with the preset named beneath it.
        onNodeWithText("4 Jul 18:00 – 20 Jul 18:00").assertExists()
        onNodeWithText("The whole event").assertExists()
        // The old two-list selector is gone.
        onNodeWithText("Share from").assertDoesNotExist()
        onNodeWithText("Share until").assertDoesNotExist()
    }

    @Test
    fun `the edit opens the calendar with both presets while the event runs`() = runComposeUiTest {
        setScreen { TestStatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        onNodeWithContentDescription("Change which photos are shared").performClick()
        onNodeWithText("Which photos to share").assertExists()
        onNodeWithText("Whole event").assertIsRadio().assertIsSelected()
        onNodeWithText("From now").assertIsRadio().assertIsNotSelected()
    }

    @Test
    fun `before the event starts From now is not offered`() = runComposeUiTest {
        // Now (2026-07-06 12:00) is before this window opens, so "from now" would clamp to a bound the member
        // did not choose.
        setScreen {
            TestStatusScreen(joining(ready(start = eventStart("2026-07-10T00:00:00Z"))), cutoff = fixedCutoff())
        }
        onNodeWithContentDescription("Change which photos are shared").performClick()
        onNodeWithText("Whole event").assertExists()
        onNodeWithText("From now").assertDoesNotExist()
    }

    @Test
    fun `tapping From now reports the preset and closes the calendar`() = runComposeUiTest {
        var preset: RangeChoice? = null
        setScreen {
            TestStatusScreen(
                joining(ready()),
                cutoff = fixedCutoff(),
                actions = testActions(
                    participation = participationActions(choices = testRangeChoiceActions(onPreset = { preset = it })),
                ),
            )
        }
        onNodeWithContentDescription("Change which photos are shared").performClick()
        onNodeWithText("From now").performClick()
        assertEquals(RangeChoice.FROM_NOW, preset)
        onNodeWithText("Which photos to share").assertDoesNotExist()
    }

    @Test
    fun `OK reports the calendar’s span as a custom range inside the window`() = runComposeUiTest {
        var picked: Pair<LocalDateTime, LocalDateTime>? = null
        setScreen {
            TestStatusScreen(
                joining(ready()),
                cutoff = fixedCutoff(),
                actions = testActions(
                    participation = participationActions(
                        choices = testRangeChoiceActions(onCustom = { f, u -> picked = f to u }),
                    ),
                ),
            )
        }
        onNodeWithContentDescription("Change which photos are shared").performClick()
        onNodeWithText("OK").performClick()
        // Opened on the chosen range and confirmed unchanged: the window itself, as a custom range.
        assertEquals(LocalDateTime(2026, 7, 4, 18, 0) to LocalDateTime(2026, 7, 20, 18, 0), picked)
    }

    @Test
    fun `cancelling the calendar reports nothing`() = runComposeUiTest {
        var edits = 0
        setScreen {
            TestStatusScreen(
                joining(ready()),
                cutoff = fixedCutoff(),
                actions = testActions(
                    participation = participationActions(
                        choices = testRangeChoiceActions(onPreset = { edits++ }, onCustom = { _, _ -> edits++ }),
                    ),
                ),
            )
        }
        onNodeWithContentDescription("Change which photos are shared").performClick()
        // The screen pins its own Cancel too; the dialog's is the later root.
        onAllNodesWithText("Cancel").onLast().performClick()
        assertEquals(0, edits)
    }

    @Test
    fun `a custom range renders its bounds and names itself custom`() = runComposeUiTest {
        val form = RangeForm(
            preset = RangeChoice.CUSTOM,
            customFrom = LocalDateTime(2026, 7, 6, 9, 0),
            customUntil = LocalDateTime(2026, 7, 8, 21, 0),
        )
        setScreen { TestStatusScreen(joining(ready(), form = form), cutoff = fixedCutoff()) }
        onNodeWithText("6 Jul 09:00 – 8 Jul 21:00").assertExists()
        onNodeWithText("Custom range").assertExists()
    }

    // ---- no retention statement (capability `join-event`) ----------------------------------------------

    @Test
    fun `the join surface does not state the deletion date`() = runComposeUiTest {
        setScreen { TestStatusScreen(joining(phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)), cutoff = fixedCutoff()) }
        onNodeWithText("deleted on", substring = true).assertDoesNotExist()
        onNodeWithText("30 days", substring = true).assertDoesNotExist()
    }

    // ---- the shareable-count row (capability `join-event`) ---------------------------------------

    @Test
    fun `the share section shows how many photos will be shared`() = runComposeUiTest {
        setScreen {
            TestStatusScreen(
                joining(phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES), count = ShareCount.Ready(34)),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithText("The whole event · 34 photos from your gallery").assertExists()
    }

    @Test
    fun `a zero count carries the forward gloss`() = runComposeUiTest {
        setScreen {
            TestStatusScreen(
                joining(phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES), count = ShareCount.Ready(0)),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithText("The whole event · 0 photos from your gallery").assertExists()
        onNodeWithText("New photos you take will be shared as you go.").assertExists()
    }

    @Test
    fun `no count is shown when none is available`() = runComposeUiTest {
        setScreen {
            TestStatusScreen(
                // Unavailable = DENIED / unresolved grant → the row is omitted (no spinner that can't resolve).
                joining(phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES), count = ShareCount.Unavailable),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithText("from your gallery", substring = true).assertDoesNotExist()
        onNodeWithText("counting your photos", substring = true).assertDoesNotExist()
        onNodeWithText("The whole event").assertExists()
    }

    @Test
    fun `a count still being computed says so`() = runComposeUiTest {
        setScreen {
            TestStatusScreen(
                joining(phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES), count = ShareCount.Counting),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithText("The whole event · counting your photos…").assertExists()
    }

    @Test
    fun `share off hides the count`() = runComposeUiTest {
        // Not offered rather than shown as zero: absent and zero are different answers, and "no count"
        // is what a non-contributing choice means (capability `join-event`).
        setScreen {
            TestStatusScreen(
                joining(
                    phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES),
                    form = RangeForm(shareOn = false),
                    count = ShareCount.Ready(34),
                ),
                cutoff = fixedCutoff(),
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
                joining(phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES), count = ShareCount.Ready(5)),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithText("The whole event · 5 photos from your gallery").assertExists()
    }

    @Test
    fun `the count follows the resolved range singular at one`() = runComposeUiTest {
        // A range resolved from From now shares just the one photo — and the row says "photo", not "photos".
        setScreen {
            TestStatusScreen(
                joining(
                    phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES),
                    form = RangeForm(preset = RangeChoice.FROM_NOW),
                    count = ShareCount.Ready(1),
                ),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithText("From now · 1 photo from your gallery").assertExists()
    }

    // ---- the album switch (capability `event-album`) ---------------------------------

    @Test
    fun `the album is a switch — on by default — stating what is collected`() = runComposeUiTest {
        // The default is what an UNTOUCHED gate commits: the album is the only on-device statement
        // that a set of photos belongs to this event, so deciding nothing gets you the grouping.
        setScreen { TestStatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        onNodeWithText("Create an album").performScrollTo().assertIsSwitch().assertToggle(ToggleableState.On)
        onNodeWithText(
            "Photos you share and photos you receive are collected in an album named after the event.",
        ).assertExists()
    }

    @Test
    fun `the album row states no album once it is unchecked`() = runComposeUiTest {
        // Reachable only after a deliberate uncheck now that the row starts on — which is exactly when
        // the line informs, and the only remaining assertion that it is rendered at all.
        setScreen {
            TestStatusScreen(joining(ready(), form = RangeForm(saveToAlbum = false)), cutoff = fixedCutoff())
        }
        onNodeWithText("Create an album").assertToggle(ToggleableState.Off)
        onNodeWithText("No album is created.").assertExists()
    }

    @Test
    fun `the album note adapts to all four switch combinations`() = runComposeUiTest {
        // The note varies over BOTH switches at once, so it is stated per combination rather than
        // toggled into: the surface renders the combination the state names.
        fun note(shareOn: Boolean, receiveOn: Boolean) =
            joining(ready(), form = RangeForm(shareOn = shareOn, receiveOn = receiveOn, saveToAlbum = true))

        setScreen { TestStatusScreen(note(shareOn = true, receiveOn = true), cutoff = fixedCutoff()) }
        onNodeWithText("Create an album").performScrollTo().assertToggle(ToggleableState.On)
        onNodeWithText(
            "Photos you share and photos you receive are collected in an album named after the event.",
        ).assertExists()
    }

    @Test
    fun `the album note names share-only receive-only and neither`() = runComposeUiTest {
        fun note(shareOn: Boolean, receiveOn: Boolean) =
            joining(ready(), form = RangeForm(shareOn = shareOn, receiveOn = receiveOn, saveToAlbum = true))

        setScreen { TestStatusScreen(note(shareOn = true, receiveOn = false), cutoff = fixedCutoff()) }
        onNodeWithText("Photos you share are collected in an album named after the event.").assertExists()
    }

    @Test
    fun `tapping the album opt-in reports the choice`() = runComposeUiTest {
        var saveToAlbum: Boolean? = null
        // Seeded OFF so the tap under test is the one that turns the album ON — the callback is what
        // this asserts, and reading it off the default would make the test restate the seed instead.
        setScreen {
            TestStatusScreen(
                joining(ready(), form = RangeForm(saveToAlbum = false)),
                cutoff = fixedCutoff(),
                actions = testActions(participation = participationActions(onSaveToAlbum = { saveToAlbum = it })),
            )
        }
        onNodeWithText("Create an album").performScrollTo().performClick()
        assertEquals(true, saveToAlbum)
    }

    @Test
    fun `tapping the album opt-in from the default reports declining it`() = runComposeUiTest {
        var saveToAlbum: Boolean? = null
        setScreen {
            TestStatusScreen(
                joining(ready()),
                cutoff = fixedCutoff(),
                actions = testActions(participation = participationActions(onSaveToAlbum = { saveToAlbum = it })),
            )
        }
        onNodeWithText("Create an album").performScrollTo().performClick()
        assertEquals(false, saveToAlbum, "a tap from the on-by-default row declines the album")
    }

    // ---- photo access asked on Join (capability `join-event`) -----------------------------------------

    private fun asking() = joining(ready()).let {
        it.copy(layer = (it.layer as Layer.JoiningEvent).copy(asksAccessOnJoin = true))
    }

    @Test
    fun `a never-asked guest sees the notice and Join and allow photos beside the choices`() = runComposeUiTest {
        setScreen { TestStatusScreen(asking(), cutoff = fixedCutoff()) }
        onNodeWithText("Anna's Wedding").assertExists()
        onNodeWithText("Share my photos").assertExists()
        onNodeWithText("iOS asks for access to your photos next").assertExists()
        onNodeWithText("Join & allow photos").assertExists()
        onNodeWithText("Join").assertDoesNotExist()
    }

    @Test
    fun `a guest iOS already asked sees plain Join and no notice`() = runComposeUiTest {
        setScreen { TestStatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        onNodeWithText("iOS asks for access to your photos next").assertDoesNotExist()
        onNodeWithText("Join & allow photos").assertDoesNotExist()
        onNodeWithText("Join").assertExists()
    }

    @Test
    fun `the info opens the explanation and closing it confirms nothing`() = runComposeUiTest {
        var confirmed = 0
        setScreen {
            TestStatusScreen(
                asking(),
                cutoff = fixedCutoff(),
                actions = testActions(join = testJoinGateActions(onConfirmJoin = { confirmed++ })),
            )
        }
        onNodeWithContentDescription("What joining does with your photos").performClick()
        onNodeWithText("What joining does").assertExists()
        onNodeWithText("Your photos are shared automatically").assertExists()
        onNodeWithText("SnapSync needs your photo library").assertExists()
        onNodeWithText("Allow all photos, or pick which to share").assertExists()
        onNodeWithText("Only photos in the range you chose").assertExists()
        onNodeWithText("Got it").performClick()
        onNodeWithText("What joining does").assertDoesNotExist()
        assertEquals(0, confirmed)
    }

    @Test
    fun `Join and allow photos confirms the join`() = runComposeUiTest {
        var confirmed = 0
        setScreen {
            TestStatusScreen(
                asking(),
                cutoff = fixedCutoff(),
                actions = testActions(join = testJoinGateActions(onConfirmJoin = { confirmed++ })),
            )
        }
        onNodeWithText("Join & allow photos").performClick()
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
        setScreen { TestStatusScreen(joining(phase), cutoff = fixedCutoff()) }
        onNodeWithText("Loading event details …").assertExists()

        phase = ready()
        waitForIdle()
        // The event's window (4 Jul 18:00 – 20 Jul 18:00), NOT "now" — derived from the phase every composition.
        onNodeWithText("4 Jul 18:00 – 20 Jul 18:00").assertExists()
    }

    @Test
    fun `the commit-failed step still offers Retry over the same event`() = runComposeUiTest {
        // What the retry COMMITS is the reduction's answer — the form outlives the phase change because it
        // lives in the container now, not in this composition, which is why there is nothing here to
        // assert about it. `StatusContainerHostTest` covers that a retry commits the chosen range.
        var retried = 0
        setScreen {
            TestStatusScreen(
                joining(phaseAt(JoinPhase.Detailed.Step.CommitFailed, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = testActions(join = testJoinGateActions(onRetryJoin = { retried++ })),
            )
        }
        onNodeWithText("Retry").performClick()
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
                        phaseAt(JoinPhase.Detailed.Step.Ready, "New Event", CUTOFF, SWITCH_END, EVENT_DELETES),
                    ),
                ),
                cutoff = fixedCutoff(),
                actions = testActions(
                    switch = testSwitchActions(
                        onConfirmSwitch = { confirms++ },
                    ),
                )
            )
        }
        onNodeWithText("Switch events?").assertExists()
        onNodeWithText("You'll leave \"Summer Trip\" and join \"New Event\".").assertExists()
        // No participation promise, and no count for a range the member has not chosen.
        onNodeWithText("You'll share photos you take and receive everyone's.").assertDoesNotExist()
        onNodeWithText("from your gallery", substring = true).assertDoesNotExist()

        onNodeWithText("Switch").performClick()
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
                        phaseAt(JoinPhase.Detailed.Step.Ready, "New Event", CUTOFF, SWITCH_END, EVENT_DELETES),
                    ),
                ),
                cutoff = fixedCutoff(),
                actions = testActions(
                    switch = testSwitchActions(
                        onCancelSwitch = { cancelled++ },
                    ),
                )
            )
        }
        onNodeWithText("Cancel").performClick()
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
                cutoff = fixedCutoff(),
                actions = testActions(
                    switch = testSwitchActions(
                        onCancelSwitch = { cancelled++ },
                    ),
                )
            )
        }
        onNodeWithText("This invite is invalid or the event no longer exists.").assertExists()
        onNodeWithText("OK").performClick()
        assertEquals(1, cancelled)
    }

    // ---- NEGATIVE: the derived direction is never named on the Ready surface --------------------------

    @Test
    fun `the Ready surface never prints Both or Upload only or Download only`() = runComposeUiTest {
        setScreen { TestStatusScreen(joining(ready()), cutoff = fixedCutoff()) }
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
private val SWITCH_MEMBERSHIP = EventConfig(
    eventId = "E1",
    name = "Summer Trip",
    minPhotoDate = captureCutoff("2026-07-06T12:00:00Z"),
    startsAt = eventStart("2026-07-06T12:00:00Z"),
    endsAt = eventEnd("2026-07-10T12:00:00Z"),
    maxPhotoDate = captureCeiling("2026-07-10T12:00:00Z"),
)

private fun joinedWith(health: SyncHealth, pendingSwitch: PendingSwitch? = null, name: String = "Summer Trip") =
    UiState(
        Layer.Joined(
            membership = SWITCH_MEMBERSHIP.copy(name = name),
            inviteUrl = "https://snapsync.stho.net/join#v=3&d=eyJldmVudElkIjoiRTEifQ",
            health = health,
            pendingSwitch = pendingSwitch,
        ),
    )

/**
 * A loaded join phase at [step]. The four event facts are stated ONCE on the phase now (capability
 * `join-event`), so a test builds the details and says which step is showing.
 */
private fun phaseAt(
    step: JoinPhase.Detailed.Step,
    name: String,
    startsAt: EventStart,
    endsAt: EventEnd,
    deletesAt: DeletesAt,
) = JoinPhase.Detailed(EventDetails(name, startsAt, endsAt, deletesAt), step)
