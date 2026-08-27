@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.ui

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
import app.snapsync.presentation.JoinPhase
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.PendingSwitch
import app.snapsync.presentation.SyncHealth
import app.snapsync.presentation.UiState
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.TimeZone

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

    private fun joining(phase: JoinPhase) = UiState.JoiningEvent("11111111-1111-4111-8111-111111111111", phase)

    private fun ready(start: EventStart = EVENT_START, end: EventEnd = EVENT_END) =
        JoinPhase.Ready("Anna's Wedding", start, end, EVENT_DELETES)

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
        setScreen { StatusScreen(joining(JoinPhase.LoadFailed), cutoff = fixedCutoff(), actions = StatusActions(onRetryLoad = { retried++ })) }
        onNodeWithText("Retry").assertExists()
        onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `commit-failed phase offers Retry for the join`() = runComposeUiTest {
        var retried = 0
        setScreen {
            StatusScreen(
                joining(JoinPhase.CommitFailed("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    onRetryJoin = { _, _, _, _ -> retried++ },
                ),
            )
        }
        onNodeWithText("Couldn't join").assertExists()
        onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
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
    fun `toggling the share switch flips only it and swaps its consequence line`() = runComposeUiTest {
        setScreen { StatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        onNodeWithText(
            "Screenshots, screen recordings, GIFs and pictures saved from chat apps are never shared.",
        ).assertExists()

        onNodeWithText("Share my photos").performClick()

        onNodeWithText("Share my photos").assertToggle(ToggleableState.Off)
        onNodeWithText("Receive everyone's photos").assertToggle(ToggleableState.On)
        onNodeWithText("Nothing of yours leaves this phone.").assertExists()
    }

    @Test
    fun `both switches on derives Both`() = runComposeUiTest {
        var direction: Direction? = null
        setScreen {
            StatusScreen(joining(ready()), cutoff = fixedCutoff(), actions = StatusActions(onConfirmJoin = { _, _, d, _ -> direction = d }))
        }
        onNodeWithText("Join").performClick()
        assertEquals(Direction.Both, direction)
    }

    @Test
    fun `share only derives upload-only`() = runComposeUiTest {
        var direction: Direction? = null
        setScreen {
            StatusScreen(joining(ready()), cutoff = fixedCutoff(), actions = StatusActions(onConfirmJoin = { _, _, d, _ -> direction = d }))
        }
        // The expanded range selector sits between Share and Receive, so Receive is below the offscreen
        // viewport — scroll it into view before the click (Compose's performClick does not auto-scroll).
        onNodeWithText("Receive everyone's photos").performScrollTo().performClick() // receive off
        onNodeWithText("Join").performClick()
        assertEquals(Direction.UploadOnly, direction)
    }

    @Test
    fun `receive only derives download-only`() = runComposeUiTest {
        var direction: Direction? = null
        setScreen {
            StatusScreen(joining(ready()), cutoff = fixedCutoff(), actions = StatusActions(onConfirmJoin = { _, _, d, _ -> direction = d }))
        }
        onNodeWithText("Share my photos").performClick() // share off
        onNodeWithText("Join").performClick()
        assertEquals(Direction.DownloadOnly, direction)
    }

    @Test
    fun `both switches off disables Join with a stated reason and never auto-flips`() = runComposeUiTest {
        var confirmed = 0
        setScreen {
            StatusScreen(joining(ready()), cutoff = fixedCutoff(), actions = StatusActions(onConfirmJoin = { _, _, _, _ -> confirmed++ }))
        }
        onNodeWithText("Share my photos").performClick()
        onNodeWithText("Receive everyone's photos").performClick()

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

    @Test
    fun `selecting Now moves the lower bound to the current instant`() = runComposeUiTest {
        var committed: CaptureCutoff? = null
        setScreen {
            StatusScreen(joining(ready()), cutoff = fixedCutoff(), actions = StatusActions(onConfirmJoin = { c, _, _, _ -> committed = c }))
        }
        onNodeWithTag("from-now").performClick()
        onNodeWithTag("from-now").assertIsSelected()
        onNodeWithText("Sharing 6 Jul 12:00 – 20 Jul 18:00").assertExists()
        onNodeWithText("Join").performClick()
        assertEquals(NOW, committed, "the committed lower bound follows the Now selection")
    }

    @Test
    fun `the confirm carries the full window's from and until by default`() = runComposeUiTest {
        var from: CaptureCutoff? = null
        var until: CaptureCeiling? = null
        setScreen {
            StatusScreen(
                joining(ready()),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    onConfirmJoin = { c, u, _, _ -> from = c; until = u },
                ),
            )
        }
        onNodeWithText("Join").performClick()
        assertEquals(CaptureCutoff(EVENT_START.at), from)
        assertEquals(CaptureCeiling(EVENT_END.at), until)
    }

    @Test
    fun `selecting Event start commits the event start`() = runComposeUiTest {
        var committed: CaptureCutoff? = null
        setScreen {
            StatusScreen(joining(ready()), cutoff = fixedCutoff(), actions = StatusActions(onConfirmJoin = { c, _, _, _ -> committed = c }))
        }
        onNodeWithTag("from-now").performClick()
        onNodeWithTag("from-event-start").performClick()
        onNodeWithTag("from-event-start").assertIsSelected()
        onNodeWithText("Sharing 4 Jul 18:00 – 20 Jul 18:00").assertExists()
        onNodeWithText("Join").performClick()
        assertEquals(CaptureCutoff(EVENT_START.at), committed)
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
    fun `tapping the From Custom opens the picker and OK commits a floor-coerced lower bound`() = runComposeUiTest {
        var committed: CaptureCutoff? = null
        setScreen {
            StatusScreen(joining(ready()), cutoff = fixedCutoff(), actions = StatusActions(onConfirmJoin = { c, _, _, _ -> committed = c }))
        }
        onNodeWithText("Date & time").assertDoesNotExist()

        onNodeWithTag("from-custom").performClick()
        onNodeWithText("Date & time").assertExists()

        // OK at the seed (the window start = event start) commits Custom, on/above the floor.
        onNodeWithText("OK").performClick()
        onNodeWithTag("from-custom").assertIsSelected()
        onNodeWithText("Can't be earlier than the event started, 4 Jul 2026, 18:00.").assertExists()
        onNodeWithText("Join").performClick()
        assertEquals(CaptureCutoff(EVENT_START.at), committed, "the committed custom lower bound is coerced up to the floor")
    }

    @Test
    fun `tapping the Until Custom opens the picker and OK commits a ceiling-coerced upper bound`() = runComposeUiTest {
        var committedUntil: CaptureCeiling? = null
        setScreen {
            StatusScreen(joining(ready()), cutoff = fixedCutoff(), actions = StatusActions(onConfirmJoin = { _, u, _, _ -> committedUntil = u }))
        }
        onNodeWithTag("until-custom").performClick()
        onNodeWithText("Date & time").assertExists()
        // OK at the seed (the window end = event end) commits Custom at/below the ceiling.
        onNodeWithText("OK").performClick()
        onNodeWithTag("until-custom").assertIsSelected()
        onNodeWithText("Join").performClick()
        assertEquals(CaptureCeiling(EVENT_END.at), committedUntil, "the committed custom upper bound is coerced down to the ceiling")
    }

    // ---- the retention statement (capability `event-limits`) ------------------------------------------

    @Test
    fun `the join surface states the retention deadline and the fixed ceiling`() = runComposeUiTest {
        // The ONE place the app states retention. The creator passes through this same gate right after
        // minting, so a single line serves the host and every guest.
        setScreen {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
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
                joining(JoinPhase.Ready("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    shareableCount = { _, _ -> 34 },
                ),
            )
        }
        onNodeWithText("34 photos from your gallery will be shared").assertExists()
    }

    @Test
    fun `a zero count carries the forward gloss`() = runComposeUiTest {
        setScreen {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    shareableCount = { _, _ -> 0 },
                ),
            )
        }
        onNodeWithText("0 photos from your gallery will be shared").assertExists()
        onNodeWithText("New photos you take will be shared as you go").assertExists()
    }

    @Test
    fun `no count is shown when none is available`() = runComposeUiTest {
        setScreen {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    // null = DENIED / unresolved grant → the row is omitted (no spinner that can't resolve).
                    shareableCount = { _, _ -> null },
                ),
            )
        }
        onNodeWithText("from your gallery will be shared", substring = true).assertDoesNotExist()
        onNodeWithText("Counting your photos…").assertDoesNotExist()
    }

    @Test
    fun `turning share off hides the count`() = runComposeUiTest {
        setScreen {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    shareableCount = { _, _ -> 34 },
                ),
            )
        }
        onNodeWithText("34 photos from your gallery will be shared").assertExists()
        onNodeWithText("Share my photos").performClick()
        onNodeWithText("34 photos from your gallery will be shared").assertDoesNotExist()
    }

    @Test
    fun `the count recomputes as the cutoff changes`() = runComposeUiTest {
        setScreen {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    // A cutoff-dependent count: Now shares just 1 (singular), Event start reaches back to 5.
                    shareableCount = { c, _ -> if (c == NOW) 1 else 5 },
                ),
            )
        }
        onNodeWithText("5 photos from your gallery will be shared").assertExists()
        onNodeWithText("Now").performClick()
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
        setScreen { StatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        // Both switches on + album on. The album and Receive rows sit below the expanded range selector, so
        // scroll each into the offscreen viewport before clicking (Compose's performClick never auto-scrolls).
        onNodeWithText("Create an album").performScrollTo().performClick()
        onNodeWithText("Create an album").assertToggle(ToggleableState.On)
        onNodeWithText(
            "Photos you share and photos you receive are collected in an album named after the event.",
        ).assertExists()

        // Receive off → share only.
        onNodeWithText("Receive everyone's photos").performScrollTo().performClick()
        onNodeWithText("Photos you share are collected in an album named after the event.").assertExists()

        // Share off, receive back on → receive only.
        onNodeWithText("Share my photos").performScrollTo().performClick()
        onNodeWithText("Receive everyone's photos").performScrollTo().performClick()
        onNodeWithText("Photos you receive are collected in an album named after the event.").assertExists()

        // Both off → nothing feeds the album.
        onNodeWithText("Receive everyone's photos").performScrollTo().performClick()
        onNodeWithText("Nothing is shared or received, so nothing is collected.").assertExists()
    }

    @Test
    fun `the album opt-in is carried across the confirm callback`() = runComposeUiTest {
        var saveToAlbum: Boolean? = null
        setScreen {
            StatusScreen(joining(ready()), cutoff = fixedCutoff(), actions = StatusActions(onConfirmJoin = { _, _, _, s -> saveToAlbum = s }))
        }
        // The album row is at the bottom, below the expanded range selector — scroll it into view first.
        onNodeWithText("Create an album").performScrollTo().performClick()
        onNodeWithText("Join").performClick()
        assertEquals(true, saveToAlbum)
    }

    // ---- the photo-access explainer names the event (capability `join-event`) -------------------------

    @Test
    fun `explain-access names the event and states the three consent facts`() = runComposeUiTest {
        setScreen {
            StatusScreen(
                joining(JoinPhase.ExplainAccess("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
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
                joining(JoinPhase.ExplainAccess("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    onAcknowledgeAccess = { acknowledged++ },
                ),
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
                joining(JoinPhase.ExplainAccess("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    onCancelJoin = { cancelled++ },
                ),
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

        phase = JoinPhase.ExplainAccess("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)
        waitForIdle()
        onNodeWithText("I understand").assertExists()

        phase = ready()
        waitForIdle()
        // The event's window (4 Jul 18:00 – 20 Jul 18:00), NOT "now" — derived from the phase every composition.
        onNodeWithText("Sharing 4 Jul 18:00 – 20 Jul 18:00").assertExists()
    }

    @Test
    fun `a retry after a failed commit still carries the event window and not now`() = runComposeUiTest {
        var retriedFrom: CaptureCutoff? = null
        var retriedUntil: CaptureCeiling? = null
        setScreen {
            StatusScreen(
                joining(JoinPhase.CommitFailed("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    onRetryJoin = { c, u, _, _ -> retriedFrom = c; retriedUntil = u },
                ),
            )
        }
        onNodeWithText("Retry").performClick()
        assertEquals(CaptureCutoff(EVENT_START.at), retriedFrom, "the retry must carry the event start, not now")
        assertEquals(CaptureCeiling(EVENT_END.at), retriedUntil, "the retry must carry the event end")
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
                UiState.Joined(
                    SyncHealth.Loading,
                    PendingSwitch(
                        "22222222-2222-4222-8222-222222222222",
                        JoinPhase.Ready("New Event", CUTOFF, SWITCH_END, EVENT_DELETES),
                    ),
                ),
                eventName = "Summer Trip",
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    onConfirmSwitch = { confirms++ },
                    shareableCount = { _, _ -> 42 },
                ),
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
                UiState.Joined(
                    SyncHealth.Loading,
                    PendingSwitch(
                        "22222222-2222-4222-8222-222222222222",
                        JoinPhase.Ready("New Event", CUTOFF, SWITCH_END, EVENT_DELETES),
                    ),
                ),
                eventName = "Summer Trip",
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    onCancelSwitch = { cancelled++ },
                ),
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
                UiState.Joined(
                    SyncHealth.Loading,
                    PendingSwitch("22222222-2222-4222-8222-222222222222", JoinPhase.NotFound),
                ),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    onCancelSwitch = { cancelled++ },
                ),
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
