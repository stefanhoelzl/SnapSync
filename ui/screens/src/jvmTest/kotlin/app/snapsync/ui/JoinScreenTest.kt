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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * The **redesigned join gate** (capability `join-event`): two participation switches whose combination
 * DERIVES the direction, a 3-option cutoff (Now / Event start / Custom), a standalone album opt-in, and the
 * event-naming photo-access explainer. This suite asserts the new surface; the arrows-only selector, the
 * two-preset segmented cutoff, the "From <date>" caption, and the old "Photo access" explainer copy are gone.
 */

/** The loaded phase always carries a cutoff — the host resolves an absent `createdAt` to now. */
private const val CUTOFF = "2026-07-06T14:32:11Z"

/** "Now" for the fixed test clock. */
private const val NOW = "2026-07-06T12:00:00Z"

/** An event that has ALREADY started (before [NOW]) — the ordinary case. */
private const val EVENT_START = "2026-07-04T18:00:00Z"

/** An event that has NOT started yet (after [NOW]) — where the "Now" preset collapses onto the floor. */
private const val FUTURE_START = "2026-07-09T18:00:00Z"

class JoinScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun joining(phase: JoinPhase) = UiState.JoiningEvent("11111111-1111-4111-8111-111111111111", phase)

    /**
     * Mounts the screen under **reduced motion**. The Custom cutoff picker's time wheels animate on open
     * (a `LazyColumn` settle), and an animating scene never reaches idle — so a picker-opening test without
     * this snaps-instead-of-animates flag stalls `waitForIdle` for ~16 min. Reduce motion is semantics-neutral
     * here (this suite asserts state, never pixels), so every test uses it.
     */
    private fun setScreen(content: @Composable () -> Unit) =
        rule.setContent { CompositionLocalProvider(LocalReduceMotion provides true) { content() } }

    /**
     * A REAL formatter on a fixed clock (UTC), not a constant-returning stub: the join surface decides
     * whether the event has started by comparing `startsAt` against "now", so a formatter that ignored its
     * input could not express the pre-start case at all.
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
                joining(JoinPhase.CommitFailed("Anna's Wedding", EVENT_START)),
                onRetryJoin = { _, _, _ -> retried++ },
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
        setScreen {
            StatusScreen(joining(JoinPhase.Ready("Anna's Wedding", EVENT_START)), cutoff = fixedCutoff())
        }
        rule.onNodeWithText("Anna's Wedding").assertExists()
        // Each section header is ONE switch node — Role.Switch, and its title merges into it.
        rule.onNodeWithText("Share my photos").assertIsSwitch().assertToggle(ToggleableState.On)
        rule.onNodeWithText("Receive everyone's photos").assertIsSwitch().assertToggle(ToggleableState.On)
    }

    @Test
    fun `toggling the share switch flips only it and swaps its consequence line`() {
        setScreen {
            StatusScreen(joining(JoinPhase.Ready("Anna's Wedding", EVENT_START)), cutoff = fixedCutoff())
        }
        // On: the origin-exclusions note; off: nothing leaves the phone.
        rule.onNodeWithText(
            "Screenshots, screen recordings, GIFs and pictures saved from chat apps are never shared.",
        ).assertExists()

        rule.onNodeWithText("Share my photos").performClick()

        rule.onNodeWithText("Share my photos").assertToggle(ToggleableState.Off)
        // Receive was NOT auto-flipped by touching Share.
        rule.onNodeWithText("Receive everyone's photos").assertToggle(ToggleableState.On)
        rule.onNodeWithText("Nothing of yours leaves this phone.").assertExists()
    }

    @Test
    fun `both switches on derives Both`() {
        var direction: Direction? = null
        setScreen {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", EVENT_START)),
                onConfirmJoin = { _, d, _ -> direction = d },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("Join").performClick()
        assertEquals(Direction.Both, direction)
    }

    @Test
    fun `share only derives upload-only`() {
        var direction: Direction? = null
        setScreen {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", EVENT_START)),
                onConfirmJoin = { _, d, _ -> direction = d },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("Receive everyone's photos").performClick() // receive off
        rule.onNodeWithText("Join").performClick()
        assertEquals(Direction.UploadOnly, direction)
    }

    @Test
    fun `receive only derives download-only`() {
        var direction: Direction? = null
        setScreen {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", EVENT_START)),
                onConfirmJoin = { _, d, _ -> direction = d },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("Share my photos").performClick() // share off
        rule.onNodeWithText("Join").performClick()
        assertEquals(Direction.DownloadOnly, direction)
    }

    @Test
    fun `both switches off disables Join with a stated reason and never auto-flips`() {
        var confirmed = 0
        setScreen {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", EVENT_START)),
                onConfirmJoin = { _, _, _ -> confirmed++ },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("Share my photos").performClick()
        rule.onNodeWithText("Receive everyone's photos").performClick()

        // Neither switch flipped the other: both read off.
        rule.onNodeWithText("Share my photos").assertToggle(ToggleableState.Off)
        rule.onNodeWithText("Receive everyone's photos").assertToggle(ToggleableState.Off)
        // The reason is stated, and Join is dead.
        rule.onNodeWithText(
            "Turn on sharing or receiving — a membership that does neither does nothing.",
        ).assertExists()
        // A disabled primary carries no click action, so its state is the whole assertion — it cannot fire.
        rule.onNodeWithText("Join").assertIsNotEnabled()
        assertEquals(0, confirmed)
    }

    // ---- the 3-option cutoff (capability `photo-selection-policy`) -------------------------------------

    @Test
    fun `ready shows the three cutoff rows as radio buttons with Event start selected`() {
        setScreen {
            StatusScreen(joining(JoinPhase.Ready("Anna's Wedding", EVENT_START)), cutoff = fixedCutoff())
        }
        rule.onNodeWithText("Now").assertIsRadio()
        rule.onNodeWithText("Event start").assertIsRadio().assertIsSelected()
        rule.onNodeWithText("Custom").assertIsRadio().assertIsNotSelected()
        // The bold "Shared from …" value defaults to the event start (4 Jul 18:00), NOT now (6 Jul 12:00).
        rule.onNodeWithText("Shared from 4 Jul 2026, 18:00").assertExists()
    }

    @Test
    fun `selecting Now moves the bold cutoff to the current instant`() {
        var committed: String? = null
        setScreen {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", EVENT_START)),
                onConfirmJoin = { c, _, _ -> committed = c },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("Now").performClick()
        rule.onNodeWithText("Now").assertIsSelected()
        rule.onNodeWithText("Shared from 6 Jul 2026, 12:00").assertExists()
        rule.onNodeWithText("Join").performClick()
        assertEquals(NOW, committed, "the committed cutoff follows the Now selection")
    }

    @Test
    fun `selecting Event start commits the event start`() {
        var committed: String? = null
        setScreen {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", EVENT_START)),
                onConfirmJoin = { c, _, _ -> committed = c },
                cutoff = fixedCutoff(),
            )
        }
        // Move away then back, so the assertion means the selection, not the default.
        rule.onNodeWithText("Now").performClick()
        rule.onNodeWithText("Event start").performClick()
        rule.onNodeWithText("Event start").assertIsSelected()
        rule.onNodeWithText("Shared from 4 Jul 2026, 18:00").assertExists()
        rule.onNodeWithText("Join").performClick()
        assertEquals(EVENT_START, committed)
    }

    @Test
    fun `before the event starts the Now row is disabled`() {
        setScreen {
            StatusScreen(joining(JoinPhase.Ready("Anna's Wedding", FUTURE_START)), cutoff = fixedCutoff())
        }
        rule.onNodeWithText("Now").assertIsNotEnabled()
        rule.onNodeWithText("Event start").assertIsEnabled()
        rule.onNodeWithText("Shared from 9 Jul 2026, 18:00").assertExists()
    }

    @Test
    fun `after the event has started the Now row is enabled`() {
        setScreen {
            StatusScreen(joining(JoinPhase.Ready("Anna's Wedding", EVENT_START)), cutoff = fixedCutoff())
        }
        rule.onNodeWithText("Now").assertIsEnabled()
    }

    @Test
    fun `tapping Custom opens the picker, and OK commits a floor-coerced cutoff`() {
        var committed: String? = null
        setScreen {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", EVENT_START)),
                onConfirmJoin = { c, _, _ -> committed = c },
                cutoff = fixedCutoff(),
            )
        }
        // The picker is not up yet (its title is unique to the dialog).
        rule.onNodeWithText("Date & time").assertDoesNotExist()

        rule.onNodeWithText("Custom").performClick()
        rule.onNodeWithText("Date & time").assertExists()

        // OK at the seed (which is the floor, the event start) commits Custom — the value stays on/above the
        // floor (capability `photo-selection-policy` clamps `max(chosen, startsAt)`; the UI enforces it too).
        rule.onNodeWithText("OK").performClick()
        rule.onNodeWithText("Custom").assertIsSelected()
        // While Custom is selected the row states the CONSTRAINT (never the date the bold value already shows).
        rule.onNodeWithText("Can't be earlier than the event started, 4 Jul 2026, 18:00.").assertExists()
        rule.onNodeWithText("Join").performClick()
        assertEquals(EVENT_START, committed, "the committed custom cutoff is coerced up to the floor")
    }

    // (Custom picker Cancel-restores is asserted unambiguously at the component level in
    // AppCutoffChoicesTest — at the screen level the picker's "Cancel" collides with the bottom "Cancel".)

    // ---- the standalone album minor section (capability `event-album`) ---------------------------------

    @Test
    fun `the album row is a checkbox, off by default, stating no album`() {
        setScreen {
            StatusScreen(joining(JoinPhase.Ready("Anna's Wedding", EVENT_START)), cutoff = fixedCutoff())
        }
        rule.onNodeWithText("Create an album").assertIsCheckbox().assertToggle(ToggleableState.Off)
        rule.onNodeWithText("No album is created.").assertExists()
    }

    @Test
    fun `the album note adapts to all four switch combinations`() {
        setScreen {
            StatusScreen(joining(JoinPhase.Ready("Anna's Wedding", EVENT_START)), cutoff = fixedCutoff())
        }
        // Both switches on + album on.
        rule.onNodeWithText("Create an album").performClick()
        rule.onNodeWithText("Create an album").assertToggle(ToggleableState.On)
        rule.onNodeWithText(
            "Photos you share and photos you receive are collected in an album named after the event.",
        ).assertExists()

        // Receive off → share only.
        rule.onNodeWithText("Receive everyone's photos").performClick()
        rule.onNodeWithText("Photos you share are collected in an album named after the event.").assertExists()

        // Share off, receive back on → receive only.
        rule.onNodeWithText("Share my photos").performClick()
        rule.onNodeWithText("Receive everyone's photos").performClick()
        rule.onNodeWithText("Photos you receive are collected in an album named after the event.").assertExists()

        // Both off → nothing feeds the album.
        rule.onNodeWithText("Receive everyone's photos").performClick()
        rule.onNodeWithText("Nothing is shared or received, so nothing is collected.").assertExists()
    }

    @Test
    fun `the album opt-in is carried across the confirm callback`() {
        var saveToAlbum: Boolean? = null
        setScreen {
            StatusScreen(
                joining(JoinPhase.Ready("Anna's Wedding", EVENT_START)),
                onConfirmJoin = { _, _, s -> saveToAlbum = s },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("Create an album").performClick()
        rule.onNodeWithText("Join").performClick()
        assertEquals(true, saveToAlbum)
    }

    // ---- the photo-access explainer names the event (capability `join-event`) -------------------------

    @Test
    fun `explain-access names the event and states the three consent facts`() {
        setScreen {
            StatusScreen(joining(JoinPhase.ExplainAccess("Anna's Wedding", EVENT_START)), cutoff = fixedCutoff())
        }
        // The hero now names the event it invites you to — the old surface deliberately did NOT.
        rule.onNodeWithText("Anna's Wedding").assertExists()
        rule.onNodeWithText("WHAT JOINING DOES").assertExists()
        rule.onNodeWithText("Your photos are shared automatically").assertExists()
        rule.onNodeWithText("SnapSync needs your photo library").assertExists()
        rule.onNodeWithText("Only photos after the date you choose").assertExists()
        rule.onNodeWithText("I understand").assertExists()
        rule.onNodeWithText("Cancel").assertExists()
        // The explainer precedes the confirm surface: no Join, no switch sections yet.
        rule.onNodeWithText("Join").assertDoesNotExist()
        rule.onNodeWithText("Share my photos").assertDoesNotExist()
    }

    @Test
    fun `I understand acknowledges the explainer`() {
        var acknowledged = 0
        setScreen {
            StatusScreen(
                joining(JoinPhase.ExplainAccess("Anna's Wedding", EVENT_START)),
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
                joining(JoinPhase.ExplainAccess("Anna's Wedding", EVENT_START)),
                onCancelJoin = { cancelled++ },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
    }

    // ---- regressions the redesign must preserve -------------------------------------------------------

    /**
     * The cutoff must derive from the loaded `startsAt` across the real phase sequence
     * (`Loading` → `ExplainAccess` → `Ready`), never from a stale first-composition seed — the screen
     * mounts at `Loading`, before any phase carries a start.
     */
    @Test
    fun `the cutoff shows the event start across the real phase sequence`() {
        var phase by mutableStateOf<JoinPhase>(JoinPhase.Loading)
        setScreen { StatusScreen(joining(phase), cutoff = fixedCutoff()) }
        rule.onNodeWithText("Loading event details …").assertExists()

        phase = JoinPhase.ExplainAccess("Anna's Wedding", EVENT_START)
        rule.waitForIdle()
        rule.onNodeWithText("I understand").assertExists()

        phase = JoinPhase.Ready("Anna's Wedding", EVENT_START)
        rule.waitForIdle()
        // The event's start (4 Jul 18:00), NOT "now" (6 Jul 12:00) — derived from the phase every composition.
        rule.onNodeWithText("Shared from 4 Jul 2026, 18:00").assertExists()
    }

    @Test
    fun `a retry after a failed commit still carries the event start, not now`() {
        var retried: String? = null
        setScreen {
            StatusScreen(
                joining(JoinPhase.CommitFailed("Anna's Wedding", EVENT_START)),
                onRetryJoin = { cutoff, _, _ -> retried = cutoff },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("Retry").performClick()
        assertEquals(EVENT_START, retried, "the retry must carry the event start, not now")
    }

    // ---- the switch-events dialog (a different event scanned while joined) -----------------------------

    @Test
    fun `switch dialog states the participation reset and confirms with Both`() {
        var switchedDirection: Direction? = null
        var switchedCutoff: String? = null
        setScreen {
            StatusScreen(
                UiState.Joined(
                    SyncHealth.Loading,
                    PendingSwitch("22222222-2222-4222-8222-222222222222", JoinPhase.Ready("New Event", CUTOFF)),
                ),
                eventName = "Summer Trip",
                onConfirmSwitch = { c, d -> switchedCutoff = c; switchedDirection = d },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithText("Switch events?").assertExists()
        // The body carries both names AND the participation the switch silently resets to. Asserted as the
        // full exact line: "Summer Trip" alone also matches the screen's own heading (eventName), so a
        // substring match on the name is ambiguous — the whole sentence is unique to the dialog body.
        rule.onNodeWithText(
            "You'll leave \"Summer Trip\" and join \"New Event\". " +
                "You'll share photos you take and receive everyone's.",
        ).assertExists()

        rule.onNodeWithText("Switch").performClick()
        assertEquals(Direction.Both, switchedDirection)
        assertEquals(CUTOFF, switchedCutoff)
    }

    @Test
    fun `cancelling the switch dialog fires cancel`() {
        var cancelled = 0
        setScreen {
            StatusScreen(
                UiState.Joined(
                    SyncHealth.Loading,
                    PendingSwitch("22222222-2222-4222-8222-222222222222", JoinPhase.Ready("New Event", CUTOFF)),
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
        setScreen {
            StatusScreen(joining(JoinPhase.Ready("Anna's Wedding", EVENT_START)), cutoff = fixedCutoff())
        }
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
