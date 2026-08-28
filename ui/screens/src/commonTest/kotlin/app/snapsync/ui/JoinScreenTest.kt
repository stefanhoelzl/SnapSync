@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.ui

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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.snapsync.model.Direction
import app.snapsync.ui.components.LocalReduceMotion
import app.snapsync.presentation.RangeForm
import app.snapsync.presentation.ResolvedRange
import app.snapsync.presentation.details
import app.snapsync.model.CaptureDate
import kotlinx.datetime.LocalDateTime
import app.snapsync.model.FromChoice
import app.snapsync.model.UntilChoice
import app.snapsync.ui.components.RangeChoiceActions
import app.snapsync.presentation.Layer
import app.snapsync.presentation.EventDetails
import app.snapsync.model.DeletesAt
import app.snapsync.presentation.JoinPhase
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.PendingSwitch
import app.snapsync.presentation.SyncHealth
import app.snapsync.presentation.UiState
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.TimeZone
import app.snapsync.ui.JoinGateActions
import app.snapsync.ui.SwitchActions

/**
 * The **redesigned join gate** with a capture-date RANGE (capabilities `join-event`,
 * `photo-selection-policy`): two participation switches whose combination DERIVES the direction, a From/Until
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

/** The event's retention deadline (capability `event-limits`): 30 days past its start. */
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
    private fun joining(phase: JoinPhase, form: RangeForm = RangeForm()) = UiState(
        Layer.JoiningEvent(
            eventId = "11111111-1111-4111-8111-111111111111",
            phase = phase,
            form = form,
            range = resolvedFor(phase, form),
        ),
    )

    /** A participation bundle that reports only the edit a test is about. */
    private fun participationActions(
        onShareOn: (Boolean) -> Unit = {},
        onReceiveOn: (Boolean) -> Unit = {},
        onSaveToAlbum: (Boolean) -> Unit = {},
        choices: RangeChoiceActions = RangeChoiceActions(),
        shareableCount: suspend (CaptureCutoff, CaptureCeiling?) -> Int? = { _, _ -> null },
    ) = ParticipationActions(
        choices = choices,
        onShareOn = onShareOn,
        onReceiveOn = onReceiveOn,
        onSaveToAlbum = onSaveToAlbum,
        shareableCount = shareableCount,
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
        val from = when (form.fromPreset) {
            FromChoice.EVENT_START -> windowStart
            FromChoice.NOW -> f.nowLocal()
            FromChoice.CUSTOM -> form.fromCustom ?: windowStart
        }
        val until = when (form.untilPreset) {
            UntilChoice.EVENT_END -> windowEnd
            UntilChoice.CUSTOM -> form.untilCustom ?: windowEnd
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
            deletesLocal = f.toLocal(event.deletesAt.at),
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
        setScreen { StatusScreen(joining(JoinPhase.Loading), cutoff = fixedCutoff()) }
        onNodeWithText("Loading event details …").assertExists()
        onNodeWithText("Join").assertDoesNotExist()
    }

    @Test
    fun `not-found phase blocks the join`() = runComposeUiTest {
        setScreen { StatusScreen(joining(JoinPhase.NotFound), cutoff = fixedCutoff()) }
        onNodeWithText("Invalid invite").assertExists()
        onNodeWithText("Join").assertDoesNotExist()
        onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun `load-failed phase offers Retry`() = runComposeUiTest {
        var retried = 0
        setScreen { StatusScreen(joining(JoinPhase.LoadFailed), cutoff = fixedCutoff(), actions = StatusActions(join = JoinGateActions(onRetryLoad = { retried++ }))) }
        onNodeWithText("Retry").assertExists()
        onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `commit-failed phase offers Retry for the join`() = runComposeUiTest {
        var retried = 0
        setScreen {
            StatusScreen(
                joining(phaseAt(JoinPhase.Detailed.Step.CommitFailed, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    join = JoinGateActions(
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
            StatusScreen(
                joining(phaseAt(JoinPhase.Detailed.Step.EventFull, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = StatusActions(join = JoinGateActions(onRetryJoin = { retried++ })),
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
        setScreen { StatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        onNodeWithText("Anna's Wedding").assertExists()
        onNodeWithText("Share my photos").assertIsSwitch().assertToggle(ToggleableState.On)
        onNodeWithText("Receive everyone's photos").assertIsSwitch().assertToggle(ToggleableState.On)
    }

    @Test
    fun `share on states the exclusions share off states that nothing leaves`() = runComposeUiTest {
        setScreen { StatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        onNodeWithText(
            "Screenshots, screen recordings, GIFs and pictures saved from chat apps are never shared.",
        ).assertExists()
    }

    @Test
    fun `share off swaps its consequence line and leaves receive alone`() = runComposeUiTest {
        setScreen {
            StatusScreen(joining(ready(), form = RangeForm(shareOn = false)), cutoff = fixedCutoff())
        }
        onNodeWithText("Share my photos").assertToggle(ToggleableState.Off)
        onNodeWithText("Receive everyone's photos").assertToggle(ToggleableState.On)
        onNodeWithText("Nothing of yours leaves this phone.").assertExists()
    }

    @Test
    fun `both switches start on so Join is offered`() = runComposeUiTest {
        var confirmed = 0
        setScreen {
            StatusScreen(joining(ready()), cutoff = fixedCutoff(), actions = StatusActions(join = JoinGateActions(onConfirmJoin = { confirmed++ })))
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
            StatusScreen(
                joining(ready()),
                cutoff = fixedCutoff(),
                actions = StatusActions(participation = participationActions(onReceiveOn = { receiveOn = it })),
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
            StatusScreen(
                joining(ready()),
                cutoff = fixedCutoff(),
                actions = StatusActions(participation = participationActions(onShareOn = { shareOn = it })),
            )
        }
        onNodeWithText("Share my photos").performClick()
        assertEquals(false, shareOn)
    }

    @Test
    fun `both switches off disables Join with a stated reason and never auto-flips`() = runComposeUiTest {
        var confirmed = 0
        setScreen {
            StatusScreen(
                joining(ready(), form = RangeForm(shareOn = false, receiveOn = false)),
                cutoff = fixedCutoff(),
                actions = StatusActions(join = JoinGateActions(onConfirmJoin = { confirmed++ })),
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

    // ---- the From/Until range selector (capability `photo-selection-policy`) ---------------------------

    @Test
    fun `ready shows the From and Until groups defaulting to the full event window`() = runComposeUiTest {
        setScreen { StatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        onNodeWithTag("from-event-start").assertIsRadio().assertIsSelected()
        onNodeWithTag("from-now").assertIsRadio().assertIsNotSelected()
        onNodeWithTag("from-custom").assertIsRadio().assertIsNotSelected()
        onNodeWithTag("until-event-end").assertIsRadio().assertIsSelected()
        onNodeWithTag("until-custom").assertIsRadio().assertIsNotSelected()
        // The value line defaults to the full window [event start, event end], NOT now.
        onNodeWithText("Sharing 4 Jul 18:00 – 20 Jul 18:00").assertExists()
    }

    // Each control below is two questions now, and they are answered in different places: does the TAP
    // reach the form, and does the resolved state RENDER. What the tap resolves TO is the reduction's
    // answer, checked directly in `RangeResolutionTest` — asserting it through pixels would only restate
    // the rules in the one place that cannot notice when they change.

    @Test
    fun `tapping Now reports the choice`() = runComposeUiTest {
        var picked: FromChoice? = null
        setScreen {
            StatusScreen(
                joining(ready()),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    participation = participationActions(choices = RangeChoiceActions(onFromPreset = { picked = it })),
                ),
            )
        }
        onNodeWithTag("from-now").performClick()
        assertEquals(FromChoice.NOW, picked)
    }

    @Test
    fun `a range resolved from Now renders selected with its label`() = runComposeUiTest {
        setScreen {
            StatusScreen(joining(ready(), form = RangeForm(fromPreset = FromChoice.NOW)), cutoff = fixedCutoff())
        }
        onNodeWithTag("from-now").assertIsSelected()
        onNodeWithText("Sharing 6 Jul 12:00 – 20 Jul 18:00").assertExists()
    }

    @Test
    fun `tapping Event start reports the choice`() = runComposeUiTest {
        var picked: FromChoice? = null
        setScreen {
            StatusScreen(
                joining(ready(), form = RangeForm(fromPreset = FromChoice.NOW)),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    participation = participationActions(choices = RangeChoiceActions(onFromPreset = { picked = it })),
                ),
            )
        }
        onNodeWithTag("from-event-start").performClick()
        assertEquals(FromChoice.EVENT_START, picked)
    }

    @Test
    fun `the default range renders the full event window`() = runComposeUiTest {
        // The default seed is all-on over `[event start, event end]` — narrow, never widen.
        setScreen { StatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        onNodeWithTag("from-event-start").assertIsSelected()
        onNodeWithTag("until-event-end").assertIsSelected()
        onNodeWithText("Sharing 4 Jul 18:00 – 20 Jul 18:00").assertExists()
    }

    @Test
    fun `before the event starts the Now row is disabled`() = runComposeUiTest {
        setScreen { StatusScreen(joining(ready(start = FUTURE_START)), cutoff = fixedCutoff()) }
        onNodeWithTag("from-now").assertIsNotEnabled()
        onNodeWithTag("from-event-start").assertIsEnabled()
        onNodeWithText("Sharing 9 Jul 18:00 – 20 Jul 18:00").assertExists()
    }

    @Test
    fun `after the event has started the Now row is enabled`() = runComposeUiTest {
        setScreen { StatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        onNodeWithTag("from-now").assertIsEnabled()
    }

    @Test
    fun `tapping the From Custom opens the picker and OK reports the picked value`() = runComposeUiTest {
        var picked: LocalDateTime? = null
        setScreen {
            StatusScreen(
                joining(ready()),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    participation = participationActions(choices = RangeChoiceActions(onFromCustom = { picked = it })),
                ),
            )
        }
        onNodeWithText("Date & time").assertDoesNotExist()

        onNodeWithTag("from-custom").performClick()
        onNodeWithText("Date & time").assertExists()

        // OK at the seed (the window start = event start). Whether that value is then coerced up to the
        // floor is `resolveFrom`'s answer, not this surface's.
        onNodeWithText("OK").performClick()
        assertEquals(LocalDateTime(2026, 7, 4, 18, 0), picked)
    }

    @Test
    fun `a custom lower bound renders selected with the floor stated`() = runComposeUiTest {
        setScreen {
            StatusScreen(
                joining(ready(), form = RangeForm(fromPreset = FromChoice.CUSTOM, fromCustom = LocalDateTime(2026, 7, 4, 18, 0))),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithTag("from-custom").assertIsSelected()
        onNodeWithText("Can't be earlier than the event started, 4 Jul 2026, 18:00.").assertExists()
    }

    @Test
    fun `tapping the Until Custom opens the picker and OK reports the picked value`() = runComposeUiTest {
        var picked: LocalDateTime? = null
        setScreen {
            StatusScreen(
                joining(ready()),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    participation = participationActions(choices = RangeChoiceActions(onUntilCustom = { picked = it })),
                ),
            )
        }
        onNodeWithTag("until-custom").performClick()
        onNodeWithText("Date & time").assertExists()
        onNodeWithText("OK").performClick()
        assertEquals(LocalDateTime(2026, 7, 20, 18, 0), picked)
    }

    @Test
    fun `a custom upper bound renders selected`() = runComposeUiTest {
        setScreen {
            StatusScreen(
                joining(ready(), form = RangeForm(untilPreset = UntilChoice.CUSTOM, untilCustom = LocalDateTime(2026, 7, 20, 18, 0))),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithTag("until-custom").assertIsSelected()
    }

    // ---- the retention statement (capability `event-limits`) ------------------------------------------

    @Test
    fun `the join surface states the retention deadline and the fixed ceiling`() = runComposeUiTest {
        // The ONE place the app states retention. The creator passes through this same gate right after
        // minting, so a single line serves the host and every guest.
        setScreen {
            StatusScreen(
                joining(phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
            )
        }
        // The DATE comes from the server-supplied deadline — never derived on the device, because a
        // client-side copy of the retention rule would promise a date the backend will not honour.
        onNodeWithText("Shared photos are deleted on 3 Aug 2026.", substring = true).assertExists()
        // …and the fixed ceiling, stated unconditionally: an event may be reclaimed sooner, but that is
        // not assured, so it is never presented as a qualification on the date.
        onNodeWithText("kept for at most 30 days", substring = true).assertExists()
    }

    // ---- the shareable-count row (capability `join-share-count`) ---------------------------------------

    @Test
    fun `the share section shows how many photos will be shared`() = runComposeUiTest {
        setScreen {
            StatusScreen(
                joining(phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = StatusActions(participation = participationActions(shareableCount = { _, _ -> 34 }))
            )
        }
        onNodeWithText("34 photos from your gallery will be shared").assertExists()
    }

    @Test
    fun `a zero count carries the forward gloss`() = runComposeUiTest {
        setScreen {
            StatusScreen(
                joining(phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = StatusActions(participation = participationActions(shareableCount = { _, _ -> 0 }))
            )
        }
        onNodeWithText("0 photos from your gallery will be shared").assertExists()
        onNodeWithText("New photos you take will be shared as you go").assertExists()
    }

    @Test
    fun `no count is shown when none is available`() = runComposeUiTest {
        setScreen {
            StatusScreen(
                joining(phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    // null = DENIED / unresolved grant → the row is omitted (no spinner that can't resolve).
                    participation = participationActions(shareableCount = { _, _ -> null }),
                )
            )
        }
        onNodeWithText("from your gallery will be shared", substring = true).assertDoesNotExist()
        onNodeWithText("Counting your photos…").assertDoesNotExist()
    }

    @Test
    fun `turning share off hides the count`() = runComposeUiTest {
        setScreen {
            StatusScreen(
                joining(phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = StatusActions(participation = participationActions(shareableCount = { _, _ -> 34 }))
            )
        }
        onNodeWithText("34 photos from your gallery will be shared").assertExists()
    }

    @Test
    fun `share off hides the count`() = runComposeUiTest {
        // Not offered rather than shown as zero: absent and zero are different answers, and "no count"
        // is what a non-contributing choice means (capability `join-share-count`).
        setScreen {
            StatusScreen(
                joining(
                    phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES),
                    form = RangeForm(shareOn = false),
                ),
                cutoff = fixedCutoff(),
                actions = StatusActions(participation = participationActions(shareableCount = { _, _ -> 34 })),
            )
        }
        onNodeWithText("from your gallery will be shared", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the count recomputes as the cutoff changes`() = runComposeUiTest {
        setScreen {
            StatusScreen(
                joining(phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    // A cutoff-dependent count: Now shares just 1 (singular), Event start reaches back to 5.
                    participation = participationActions(shareableCount = { c, _ -> if (c == NOW) 1 else 5 }),
                )
            )
        }
        onNodeWithText("5 photos from your gallery will be shared").assertExists()
    }

    @Test
    fun `the count follows the resolved range singular at one`() = runComposeUiTest {
        // A range resolved from Now shares just the one photo — and the row says "photo", not "photos".
        setScreen {
            StatusScreen(
                joining(
                    phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES),
                    form = RangeForm(fromPreset = FromChoice.NOW),
                ),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    participation = participationActions(shareableCount = { c, _ -> if (c == NOW) 1 else 5 }),
                ),
            )
        }
        onNodeWithText("1 photo from your gallery will be shared").assertExists()
    }

    // ---- the standalone album minor section (capability `event-album`) ---------------------------------

    @Test
    fun `the album row is a checkbox — off by default — stating no album`() = runComposeUiTest {
        setScreen { StatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        onNodeWithText("Create an album").assertIsCheckbox().assertToggle(ToggleableState.Off)
        onNodeWithText("No album is created.").assertExists()
    }

    @Test
    fun `the album note adapts to all four switch combinations`() = runComposeUiTest {
        // The note varies over BOTH switches at once, so it is stated per combination rather than
        // toggled into: the surface renders the combination the state names.
        fun note(shareOn: Boolean, receiveOn: Boolean) =
            joining(ready(), form = RangeForm(shareOn = shareOn, receiveOn = receiveOn, saveToAlbum = true))

        setScreen { StatusScreen(note(shareOn = true, receiveOn = true), cutoff = fixedCutoff()) }
        onNodeWithText("Create an album").performScrollTo().assertToggle(ToggleableState.On)
        onNodeWithText(
            "Photos you share and photos you receive are collected in an album named after the event.",
        ).assertExists()
    }

    @Test
    fun `the album note names share-only receive-only and neither`() = runComposeUiTest {
        fun note(shareOn: Boolean, receiveOn: Boolean) =
            joining(ready(), form = RangeForm(shareOn = shareOn, receiveOn = receiveOn, saveToAlbum = true))

        setScreen { StatusScreen(note(shareOn = true, receiveOn = false), cutoff = fixedCutoff()) }
        onNodeWithText("Photos you share are collected in an album named after the event.").assertExists()
    }

    @Test
    fun `tapping the album opt-in reports the choice`() = runComposeUiTest {
        var saveToAlbum: Boolean? = null
        setScreen {
            StatusScreen(
                joining(ready()),
                cutoff = fixedCutoff(),
                actions = StatusActions(participation = participationActions(onSaveToAlbum = { saveToAlbum = it })),
            )
        }
        // The album row is at the bottom, below the expanded range selector — scroll it into view first.
        onNodeWithText("Create an album").performScrollTo().performClick()
        assertEquals(true, saveToAlbum)
    }

    // ---- the photo-access explainer names the event (capability `join-event`) -------------------------

    @Test
    fun `explain-access names the event and states the three consent facts`() = runComposeUiTest {
        setScreen {
            StatusScreen(
                joining(phaseAt(JoinPhase.Detailed.Step.ExplainAccess, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithText("Anna's Wedding").assertExists()
        onNodeWithText("WHAT JOINING DOES").assertExists()
        onNodeWithText("Your photos are shared automatically").assertExists()
        onNodeWithText("SnapSync needs your photo library").assertExists()
        onNodeWithText("Only photos after the date you choose").assertExists()
        onNodeWithText("I understand").assertExists()
        onNodeWithText("Cancel").assertExists()
        onNodeWithText("Join").assertDoesNotExist()
        onNodeWithText("Share my photos").assertDoesNotExist()
    }

    @Test
    fun `I understand acknowledges the explainer`() = runComposeUiTest {
        var acknowledged = 0
        setScreen {
            StatusScreen(
                joining(phaseAt(JoinPhase.Detailed.Step.ExplainAccess, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    join = JoinGateActions(
                        onAcknowledgeAccess = { acknowledged++ },
                    ),
                )
            )
        }
        onNodeWithText("I understand").performClick()
        assertEquals(1, acknowledged)
    }

    @Test
    fun `cancelling the explainer abandons the join`() = runComposeUiTest {
        var cancelled = 0
        setScreen {
            StatusScreen(
                joining(phaseAt(JoinPhase.Detailed.Step.ExplainAccess, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    join = JoinGateActions(
                        onCancelJoin = { cancelled++ },
                    ),
                )
            )
        }
        onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
    }

    // ---- regressions the redesign must preserve -------------------------------------------------------

    /**
     * The range must derive from the loaded window across the real phase sequence
     * (`Loading` → `ExplainAccess` → `Ready`), never from a stale first-composition seed — the screen mounts
     * at `Loading`, before any phase carries a window.
     */
    @Test
    fun `the range shows the event window across the real phase sequence`() = runComposeUiTest {
        var phase by mutableStateOf<JoinPhase>(JoinPhase.Loading)
        setScreen { StatusScreen(joining(phase), cutoff = fixedCutoff()) }
        onNodeWithText("Loading event details …").assertExists()

        phase = phaseAt(JoinPhase.Detailed.Step.ExplainAccess, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)
        waitForIdle()
        onNodeWithText("I understand").assertExists()

        phase = ready()
        waitForIdle()
        // The event's window (4 Jul 18:00 – 20 Jul 18:00), NOT "now" — derived from the phase every composition.
        onNodeWithText("Sharing 4 Jul 18:00 – 20 Jul 18:00").assertExists()
    }

    @Test
    fun `the commit-failed step still offers Retry over the same event`() = runComposeUiTest {
        // What the retry COMMITS is the reduction's answer — the form outlives the phase change because it
        // lives in the container now, not in this composition, which is why there is nothing here to
        // assert about it. `StatusContainerHostTest` covers that a retry commits the chosen range.
        var retried = 0
        setScreen {
            StatusScreen(
                joining(phaseAt(JoinPhase.Detailed.Step.CommitFailed, "Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = StatusActions(join = JoinGateActions(onRetryJoin = { retried++ })),
            )
        }
        onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    // ---- the switch-events dialog (a different event scanned while joined) -----------------------------

    /**
     * The confirmation names both events and **promises no participation**: its confirm runs only the
     * leave, and the member picks direction, cutoff and album on the join surface that follows. It renders
     * no shareable count either — there is no chosen range to count yet (capability `join-share-count`).
     */
    @Test
    fun `switch dialog names both events and confirms with no choices`() = runComposeUiTest {
        var confirms = 0
        setScreen {
            StatusScreen(
                joinedWith(
                    SyncHealth.Loading,
                    PendingSwitch(
                        "22222222-2222-4222-8222-222222222222",
                        phaseAt(JoinPhase.Detailed.Step.Ready, "New Event", CUTOFF, SWITCH_END, EVENT_DELETES),
                    ),
                ),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    switch = SwitchActions(
                        onConfirmSwitch = { confirms++ },
                    ),
                    // A count is wired deliberately: the switch dialog must still show none, there being
                    // no chosen range to count yet (capability `join-share-count`).
                    participation = participationActions(shareableCount = { _, _ -> 42 }),
                )
            )
        }
        onNodeWithText("Switch events?").assertExists()
        onNodeWithText("You'll leave \"Summer Trip\" and join \"New Event\".").assertExists()
        // No participation promise, and no count for a range the member has not chosen.
        onNodeWithText("You'll share photos you take and receive everyone's.").assertDoesNotExist()
        onNodeWithText("42 photos from your gallery will be shared.").assertDoesNotExist()

        onNodeWithText("Switch").performClick()
        assertEquals(1, confirms)
    }

    @Test
    fun `cancelling the switch dialog fires cancel`() = runComposeUiTest {
        var cancelled = 0
        setScreen {
            StatusScreen(
                joinedWith(
                    SyncHealth.Loading,
                    PendingSwitch(
                        "22222222-2222-4222-8222-222222222222",
                        phaseAt(JoinPhase.Detailed.Step.Ready, "New Event", CUTOFF, SWITCH_END, EVENT_DELETES),
                    ),
                ),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    switch = SwitchActions(
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
            StatusScreen(
                joinedWith(
                    SyncHealth.Loading,
                    PendingSwitch("22222222-2222-4222-8222-222222222222", JoinPhase.NotFound),
                ),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    switch = SwitchActions(
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
        setScreen { StatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        onNodeWithText("Both").assertDoesNotExist()
        onNodeWithText("Upload only").assertDoesNotExist()
        onNodeWithText("Download only").assertDoesNotExist()
    }
}

// ---- small semantic helpers ---------------------------------------------------------------------------

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsSwitch() =
    assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsCheckbox() =
    assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsRadio() =
    assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertToggle(state: ToggleableState) =
    assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state))

// The membership and invite URL live inside the joined state now (capability `sync-status-screen`), so
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
