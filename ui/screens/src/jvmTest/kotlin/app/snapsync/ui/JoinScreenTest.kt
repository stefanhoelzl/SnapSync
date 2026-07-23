package app.snapsync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import org.junit.Rule

/**
 * The **redesigned join gate** with a capture-date RANGE (capabilities `join-event`,
 * `photo-selection-policy`): two participation switches whose combination DERIVES the direction, a From/Until
 * range selector (From: Event start / Now / Custom; Until: Event end / Custom) defaulting to the FULL event
 * window, a standalone album opt-in, and the event-naming photo-access explainer.
 */

/** The switch dialog's new-event `startsAt` / `endsAt` (a different event scanned while joined). */
private const val CUTOFF = "2026-07-06T14:32:11Z"
private const val SWITCH_END = "2026-07-16T00:00:00Z"

/** "Now" for the fixed test clock. */
private const val NOW = "2026-07-06T12:00:00Z"

/** An event that has ALREADY started (before [NOW]) — the ordinary case — and its end (after [NOW]). */
private const val EVENT_START = "2026-07-04T18:00:00Z"
private const val EVENT_END = "2026-07-20T18:00:00Z"

/** The event's retention deadline (capability `event-limits`): 30 days past its start. */
private const val EVENT_DELETES = "2026-08-03T18:00:00Z"

/** An event that has NOT started yet (after [NOW]) — where the "Now" preset falls outside the window. */
private const val FUTURE_START = "2026-07-09T18:00:00Z"

class JoinScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun joining(phase: JoinPhase) = UiState.JoiningEvent("11111111-1111-4111-8111-111111111111", phase)

    private fun ready(start: String = EVENT_START, end: String = EVENT_END) =
        JoinPhase.Ready("Anna's Wedding", start, end, EVENT_DELETES)

    /**
     * Mounts the screen under **reduced motion**. The Custom picker's time wheels animate on open (a
     * `LazyColumn` settle), and an animating scene never reaches idle — so a picker-opening test without this
     * snaps-instead-of-animates flag stalls `waitForIdle` for ~16 min. Reduce motion is semantics-neutral
     * here (this suite asserts state, never pixels), so every test uses it.
     */
    private fun setScreen(content: @Composable () -> Unit) =
        rule.setContent { CompositionLocalProvider(LocalReduceMotion provides true) { content() } }

    /**
     * A REAL formatter on a fixed clock (UTC), not a constant-returning stub: the join surface decides
     * whether the present is inside the window by comparing `startsAt`/`endsAt` against "now", so a formatter
     * that ignored its input could not express the pre-start case at all.
     */
    private fun fixedCutoff(now: String = NOW) = CutoffFormatter(
        now = { Instant.parse(now) },
        zone = TimeZone.UTC,
    )

    // ---- the in-flight / error phases (unchanged shells) ----------------------------------------------

    @Test
    fun `loading phase shows the loading label and no Join`() {
        setScreen { StatusScreen(joining(JoinPhase.Loading), cutoff = fixedCutoff()) }
        rule.onNodeWithText("Loading event details …").assertExists()
        rule.onNodeWithText("Join").assertDoesNotExist()
    }

    @Test
    fun `not-found phase blocks the join`() {
        setScreen { StatusScreen(joining(JoinPhase.NotFound), cutoff = fixedCutoff()) }
        rule.onNodeWithText("Invalid invite").assertExists()
        rule.onNodeWithText("Join").assertDoesNotExist()
        rule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun `load-failed phase offers Retry`() {
        var retried = 0
        setScreen { StatusScreen(joining(JoinPhase.LoadFailed), onRetryLoad = { retried++ }, cutoff = fixedCutoff()) }
        rule.onNodeWithText("Retry").assertExists()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `commit-failed phase offers Retry for the join`() {
        var retried = 0
        setScreen {
            StatusScreen(
                joining(JoinPhase.CommitFailed("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                onRetryJoin = { _, _, _, _ -> retried++ },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("Couldn't join").assertExists()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    // ---- the two participation switches (capability `join-event`) --------------------------------------

    @Test
    fun `ready shows the two switch sections, both on by default`() {
        setScreen { StatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        rule.onNodeWithText("Anna's Wedding").assertExists()
        rule.onNodeWithText("Share my photos").assertIsSwitch().assertToggle(ToggleableState.On)
        rule.onNodeWithText("Receive everyone's photos").assertIsSwitch().assertToggle(ToggleableState.On)
    }

    @Test
    fun `toggling the share switch flips only it and swaps its consequence line`() {
        setScreen { StatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        rule.onNodeWithText(
            "Screenshots, screen recordings, GIFs and pictures saved from chat apps are never shared.",
        ).assertExists()

        rule.onNodeWithText("Share my photos").performClick()

        rule.onNodeWithText("Share my photos").assertToggle(ToggleableState.Off)
        rule.onNodeWithText("Receive everyone's photos").assertToggle(ToggleableState.On)
        rule.onNodeWithText("Nothing of yours leaves this phone.").assertExists()
    }

    @Test
    fun `both switches on derives Both`() {
        var direction: Direction? = null
        setScreen {
            StatusScreen(joining(ready()), onConfirmJoin = { _, _, d, _ -> direction = d }, cutoff = fixedCutoff())
        }
        rule.onNodeWithText("Join").performClick()
        assertEquals(Direction.Both, direction)
    }

    @Test
    fun `share only derives upload-only`() {
        var direction: Direction? = null
        setScreen {
            StatusScreen(joining(ready()), onConfirmJoin = { _, _, d, _ -> direction = d }, cutoff = fixedCutoff())
        }
        // The expanded range selector sits between Share and Receive, so Receive is below the offscreen
        // viewport — scroll it into view before the click (Compose's performClick does not auto-scroll).
        rule.onNodeWithText("Receive everyone's photos").performScrollTo().performClick() // receive off
        rule.onNodeWithText("Join").performClick()
        assertEquals(Direction.UploadOnly, direction)
    }

    @Test
    fun `receive only derives download-only`() {
        var direction: Direction? = null
        setScreen {
            StatusScreen(joining(ready()), onConfirmJoin = { _, _, d, _ -> direction = d }, cutoff = fixedCutoff())
        }
        rule.onNodeWithText("Share my photos").performClick() // share off
        rule.onNodeWithText("Join").performClick()
        assertEquals(Direction.DownloadOnly, direction)
    }

    @Test
    fun `both switches off disables Join with a stated reason and never auto-flips`() {
        var confirmed = 0
        setScreen {
            StatusScreen(joining(ready()), onConfirmJoin = { _, _, _, _ -> confirmed++ }, cutoff = fixedCutoff())
        }
        rule.onNodeWithText("Share my photos").performClick()
        rule.onNodeWithText("Receive everyone's photos").performClick()

        rule.onNodeWithText("Share my photos").assertToggle(ToggleableState.Off)
        rule.onNodeWithText("Receive everyone's photos").assertToggle(ToggleableState.Off)
        rule.onNodeWithText(
            "Turn on sharing or receiving — a membership that does neither does nothing.",
        ).assertExists()
        rule.onNodeWithText("Join").assertIsNotEnabled()
        assertEquals(0, confirmed)
    }

    // ---- the From/Until range selector (capability `photo-selection-policy`) ---------------------------

    @Test
    fun `ready shows the From and Until groups defaulting to the full event window`() {
        setScreen { StatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        rule.onNodeWithTag("from-event-start").assertIsRadio().assertIsSelected()
        rule.onNodeWithTag("from-now").assertIsRadio().assertIsNotSelected()
        rule.onNodeWithTag("from-custom").assertIsRadio().assertIsNotSelected()
        rule.onNodeWithTag("until-event-end").assertIsRadio().assertIsSelected()
        rule.onNodeWithTag("until-custom").assertIsRadio().assertIsNotSelected()
        // The value line defaults to the full window [event start, event end], NOT now.
        rule.onNodeWithText("Sharing 4 Jul 18:00 – 20 Jul 18:00").assertExists()
    }

    @Test
    fun `selecting Now moves the lower bound to the current instant`() {
        var committed: String? = null
        setScreen {
            StatusScreen(joining(ready()), onConfirmJoin = { c, _, _, _ -> committed = c }, cutoff = fixedCutoff())
        }
        rule.onNodeWithTag("from-now").performClick()
        rule.onNodeWithTag("from-now").assertIsSelected()
        rule.onNodeWithText("Sharing 6 Jul 12:00 – 20 Jul 18:00").assertExists()
        rule.onNodeWithText("Join").performClick()
        assertEquals(NOW, committed, "the committed lower bound follows the Now selection")
    }

    @Test
    fun `the confirm carries the full window's from and until by default`() {
        var from: String? = null
        var until: String? = null
        setScreen {
            StatusScreen(
                joining(ready()),
                onConfirmJoin = { c, u, _, _ -> from = c; until = u },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("Join").performClick()
        assertEquals(EVENT_START, from)
        assertEquals(EVENT_END, until)
    }

    @Test
    fun `selecting Event start commits the event start`() {
        var committed: String? = null
        setScreen {
            StatusScreen(joining(ready()), onConfirmJoin = { c, _, _, _ -> committed = c }, cutoff = fixedCutoff())
        }
        rule.onNodeWithTag("from-now").performClick()
        rule.onNodeWithTag("from-event-start").performClick()
        rule.onNodeWithTag("from-event-start").assertIsSelected()
        rule.onNodeWithText("Sharing 4 Jul 18:00 – 20 Jul 18:00").assertExists()
        rule.onNodeWithText("Join").performClick()
        assertEquals(EVENT_START, committed)
    }

    @Test
    fun `before the event starts the Now row is disabled`() {
        setScreen { StatusScreen(joining(ready(start = FUTURE_START)), cutoff = fixedCutoff()) }
        rule.onNodeWithTag("from-now").assertIsNotEnabled()
        rule.onNodeWithTag("from-event-start").assertIsEnabled()
        rule.onNodeWithText("Sharing 9 Jul 18:00 – 20 Jul 18:00").assertExists()
    }

    @Test
    fun `after the event has started the Now row is enabled`() {
        setScreen { StatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        rule.onNodeWithTag("from-now").assertIsEnabled()
    }

    @Test
    fun `tapping the From Custom opens the picker, and OK commits a floor-coerced lower bound`() {
        var committed: String? = null
        setScreen {
            StatusScreen(joining(ready()), onConfirmJoin = { c, _, _, _ -> committed = c }, cutoff = fixedCutoff())
        }
        rule.onNodeWithText("Date & time").assertDoesNotExist()

        rule.onNodeWithTag("from-custom").performClick()
        rule.onNodeWithText("Date & time").assertExists()

        // OK at the seed (the window start = event start) commits Custom, on/above the floor.
        rule.onNodeWithText("OK").performClick()
        rule.onNodeWithTag("from-custom").assertIsSelected()
        rule.onNodeWithText("Can't be earlier than the event started, 4 Jul 2026, 18:00.").assertExists()
        rule.onNodeWithText("Join").performClick()
        assertEquals(EVENT_START, committed, "the committed custom lower bound is coerced up to the floor")
    }

    @Test
    fun `tapping the Until Custom opens the picker, and OK commits a ceiling-coerced upper bound`() {
        var committedUntil: String? = null
        setScreen {
            StatusScreen(joining(ready()), onConfirmJoin = { _, u, _, _ -> committedUntil = u }, cutoff = fixedCutoff())
        }
        rule.onNodeWithTag("until-custom").performClick()
        rule.onNodeWithText("Date & time").assertExists()
        // OK at the seed (the window end = event end) commits Custom at/below the ceiling.
        rule.onNodeWithText("OK").performClick()
        rule.onNodeWithTag("until-custom").assertIsSelected()
        rule.onNodeWithText("Join").performClick()
        assertEquals(EVENT_END, committedUntil, "the committed custom upper bound is coerced down to the ceiling")
    }

    // ---- the retention statement (capability `event-limits`) ------------------------------------------

    @Test
    fun `the join surface states the retention deadline and the fixed ceiling`() {
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
        rule.onNodeWithText("Shared photos are deleted on 3 Aug 2026.", substring = true).assertExists()
        // …and the fixed ceiling, stated unconditionally: an event may be reclaimed sooner, but that is
        // not assured, so it is never presented as a qualification on the date.
        rule.onNodeWithText("kept for at most 30 days", substring = true).assertExists()
    }

    // ---- the shareable-count row (capability `join-share-count`) ---------------------------------------

    @Test
    fun `the share section shows how many photos will be shared`() {
        setScreen {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                shareableCount = { _, _ -> 34 },
            )
        }
        rule.onNodeWithText("34 photos from your gallery will be shared").assertExists()
    }

    @Test
    fun `a zero count carries the forward gloss`() {
        setScreen {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                shareableCount = { _, _ -> 0 },
            )
        }
        rule.onNodeWithText("0 photos from your gallery will be shared").assertExists()
        rule.onNodeWithText("New photos you take will be shared as you go").assertExists()
    }

    @Test
    fun `no count is shown when none is available`() {
        setScreen {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                // null = DENIED / unresolved grant → the row is omitted (no spinner that can't resolve).
                shareableCount = { _, _ -> null },
            )
        }
        rule.onNodeWithText("from your gallery will be shared", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Counting your photos…").assertDoesNotExist()
    }

    @Test
    fun `turning share off hides the count`() {
        setScreen {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                shareableCount = { _, _ -> 34 },
            )
        }
        rule.onNodeWithText("34 photos from your gallery will be shared").assertExists()
        rule.onNodeWithText("Share my photos").performClick()
        rule.onNodeWithText("34 photos from your gallery will be shared").assertDoesNotExist()
    }

    @Test
    fun `the count recomputes as the cutoff changes`() {
        setScreen {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
                // A cutoff-dependent count: Now shares just 1 (singular), Event start reaches back to 5.
                shareableCount = { c, _ -> if (c == NOW) 1 else 5 },
            )
        }
        rule.onNodeWithText("5 photos from your gallery will be shared").assertExists()
        rule.onNodeWithText("Now").performClick()
        rule.onNodeWithText("1 photo from your gallery will be shared").assertExists()
    }

    // ---- the standalone album minor section (capability `event-album`) ---------------------------------

    @Test
    fun `the album row is a checkbox, off by default, stating no album`() {
        setScreen { StatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        rule.onNodeWithText("Create an album").assertIsCheckbox().assertToggle(ToggleableState.Off)
        rule.onNodeWithText("No album is created.").assertExists()
    }

    @Test
    fun `the album note adapts to all four switch combinations`() {
        setScreen { StatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        // Both switches on + album on. The album and Receive rows sit below the expanded range selector, so
        // scroll each into the offscreen viewport before clicking (Compose's performClick never auto-scrolls).
        rule.onNodeWithText("Create an album").performScrollTo().performClick()
        rule.onNodeWithText("Create an album").assertToggle(ToggleableState.On)
        rule.onNodeWithText(
            "Photos you share and photos you receive are collected in an album named after the event.",
        ).assertExists()

        // Receive off → share only.
        rule.onNodeWithText("Receive everyone's photos").performScrollTo().performClick()
        rule.onNodeWithText("Photos you share are collected in an album named after the event.").assertExists()

        // Share off, receive back on → receive only.
        rule.onNodeWithText("Share my photos").performScrollTo().performClick()
        rule.onNodeWithText("Receive everyone's photos").performScrollTo().performClick()
        rule.onNodeWithText("Photos you receive are collected in an album named after the event.").assertExists()

        // Both off → nothing feeds the album.
        rule.onNodeWithText("Receive everyone's photos").performScrollTo().performClick()
        rule.onNodeWithText("Nothing is shared or received, so nothing is collected.").assertExists()
    }

    @Test
    fun `the album opt-in is carried across the confirm callback`() {
        var saveToAlbum: Boolean? = null
        setScreen {
            StatusScreen(joining(ready()), onConfirmJoin = { _, _, _, s -> saveToAlbum = s }, cutoff = fixedCutoff())
        }
        // The album row is at the bottom, below the expanded range selector — scroll it into view first.
        rule.onNodeWithText("Create an album").performScrollTo().performClick()
        rule.onNodeWithText("Join").performClick()
        assertEquals(true, saveToAlbum)
    }

    // ---- the photo-access explainer names the event (capability `join-event`) -------------------------

    @Test
    fun `explain-access names the event and states the three consent facts`() {
        setScreen {
            StatusScreen(
                joining(JoinPhase.ExplainAccess("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("Anna's Wedding").assertExists()
        rule.onNodeWithText("WHAT JOINING DOES").assertExists()
        rule.onNodeWithText("Your photos are shared automatically").assertExists()
        rule.onNodeWithText("SnapSync needs your photo library").assertExists()
        rule.onNodeWithText("Only photos after the date you choose").assertExists()
        rule.onNodeWithText("I understand").assertExists()
        rule.onNodeWithText("Cancel").assertExists()
        rule.onNodeWithText("Join").assertDoesNotExist()
        rule.onNodeWithText("Share my photos").assertDoesNotExist()
    }

    @Test
    fun `I understand acknowledges the explainer`() {
        var acknowledged = 0
        setScreen {
            StatusScreen(
                joining(JoinPhase.ExplainAccess("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                onAcknowledgeAccess = { acknowledged++ },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("I understand").performClick()
        assertEquals(1, acknowledged)
    }

    @Test
    fun `cancelling the explainer abandons the join`() {
        var cancelled = 0
        setScreen {
            StatusScreen(
                joining(JoinPhase.ExplainAccess("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                onCancelJoin = { cancelled++ },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
    }

    // ---- regressions the redesign must preserve -------------------------------------------------------

    /**
     * The range must derive from the loaded window across the real phase sequence
     * (`Loading` → `ExplainAccess` → `Ready`), never from a stale first-composition seed — the screen mounts
     * at `Loading`, before any phase carries a window.
     */
    @Test
    fun `the range shows the event window across the real phase sequence`() {
        var phase by mutableStateOf<JoinPhase>(JoinPhase.Loading)
        setScreen { StatusScreen(joining(phase), cutoff = fixedCutoff()) }
        rule.onNodeWithText("Loading event details …").assertExists()

        phase = JoinPhase.ExplainAccess("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)
        rule.waitForIdle()
        rule.onNodeWithText("I understand").assertExists()

        phase = ready()
        rule.waitForIdle()
        // The event's window (4 Jul 18:00 – 20 Jul 18:00), NOT "now" — derived from the phase every composition.
        rule.onNodeWithText("Sharing 4 Jul 18:00 – 20 Jul 18:00").assertExists()
    }

    @Test
    fun `a retry after a failed commit still carries the event window, not now`() {
        var retriedFrom: String? = null
        var retriedUntil: String? = null
        setScreen {
            StatusScreen(
                joining(JoinPhase.CommitFailed("Anna's Wedding", EVENT_START, EVENT_END, EVENT_DELETES)),
                onRetryJoin = { c, u, _, _ -> retriedFrom = c; retriedUntil = u },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("Retry").performClick()
        assertEquals(EVENT_START, retriedFrom, "the retry must carry the event start, not now")
        assertEquals(EVENT_END, retriedUntil, "the retry must carry the event end")
    }

    // ---- the switch-events dialog (a different event scanned while joined) -----------------------------

    @Test
    fun `switch dialog states the participation reset and confirms with the new window and Both`() {
        var switchedDirection: Direction? = null
        var switchedCutoff: String? = null
        var switchedUntil: String? = null
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
                onConfirmSwitch = { c, u, d -> switchedCutoff = c; switchedUntil = u; switchedDirection = d },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("Switch events?").assertExists()
        rule.onNodeWithText(
            "You'll leave \"Summer Trip\" and join \"New Event\". " +
                "You'll share photos you take and receive everyone's.",
        ).assertExists()

        rule.onNodeWithText("Switch").performClick()
        assertEquals(Direction.Both, switchedDirection)
        assertEquals(CUTOFF, switchedCutoff)
        assertEquals(SWITCH_END, switchedUntil)
    }

    @Test
    fun `cancelling the switch dialog fires cancel`() {
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
                onCancelSwitch = { cancelled++ },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun `switch dialog for a missing event stays a plain confirmation and cancels`() {
        var cancelled = 0
        setScreen {
            StatusScreen(
                UiState.Joined(
                    SyncHealth.Loading,
                    PendingSwitch("22222222-2222-4222-8222-222222222222", JoinPhase.NotFound),
                ),
                onCancelSwitch = { cancelled++ },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("This invite is invalid or the event no longer exists.").assertExists()
        rule.onNodeWithText("OK").performClick()
        assertEquals(1, cancelled)
    }

    // ---- NEGATIVE: the derived direction is never named on the Ready surface --------------------------

    @Test
    fun `the Ready surface never prints Both, Upload only, or Download only`() {
        setScreen { StatusScreen(joining(ready()), cutoff = fixedCutoff()) }
        rule.onNodeWithText("Both").assertDoesNotExist()
        rule.onNodeWithText("Upload only").assertDoesNotExist()
        rule.onNodeWithText("Download only").assertDoesNotExist()
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
