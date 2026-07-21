package app.snapsync.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.snapsync.model.PermissionStatus
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.ui.components.LocalReduceMotion
import app.snapsync.model.Arrow
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.JoinPhase
import app.snapsync.presentation.PendingSwitch
import app.snapsync.presentation.SyncHealth
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import app.snapsync.presentation.UiState
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.junit.Rule

// A representative invite link — any string renders a QR; the encoding is pinned in capability:config.
private const val SAMPLE_INVITE = "https://snapsync.stho.net/join#v=3&d=eyJldmVudElkIjoiMSJ9"

private fun joined(health: SyncHealth) = UiState.Joined(health)
private val inSync = joined(SyncHealth.InSync)
private val syncing = joined(SyncHealth.Syncing(Arrow.PULSING, Arrow.HIDDEN))
private val syncPending = joined(SyncHealth.Syncing(Arrow.STATIC, Arrow.HIDDEN))

/** A clock the test can move, to prove the start default does not drift. */
private class MovableClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant
}

/** A real formatter on a fixed UTC clock — deterministic, and it actually converts. */
private fun fixedCutoff() = CutoffFormatter(
    now = { Instant.parse("2026-07-06T12:00:00Z") },
    zone = TimeZone.UTC,
)

class StatusScreenTest {

    @get:Rule
    val rule = createComposeRule()

    // ---- the not-started clock line ----

    @Test
    fun `the not-started health renders a clock line naming the start, below the QR`() {
        rule.setContent {
            StatusScreen(
                joined(SyncHealth.NotStarted("2026-07-04T18:00:00Z")),
                inviteUrl = "https://snapsync.stho.net/join#v=3&d=abc",
                cutoff = fixedCutoff(),
            )
        }
        // Rendered in the DEVICE's local zone (UTC here), in the same one-line slot every other status
        // uses — the joined layer never grows a second line.
        rule.onNodeWithText("Starts 4 Jul, 18:00").assertExists()
        // It is information, not an action: no sync arrows, no "In sync".
        rule.onNodeWithText("In sync").assertDoesNotExist()
        rule.onNodeWithText("Synchronization pending …").assertDoesNotExist()
    }

    // ---- create layer ----

    @Test
    fun `create screen shows the name input and the scan-to-join hint`() {
        rule.setContent { StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff()) }

        rule.onNodeWithText("Start an event").assertExists()
        rule.onNodeWithText("Or scan a QR code in the Camera app to join one.").assertExists()
        rule.onNodeWithText("Event name").assertExists()
        rule.onNodeWithText("Create event").assertExists()
    }

    @Test
    fun `invalid deeplink error shows on the create screen`() {
        rule.setContent {
            StatusScreen(UiState.CreateEvent(), transientError = "That QR code wasn't valid.", cutoff = fixedCutoff())
        }
        rule.onNodeWithText("That QR code wasn't valid.").assertExists()
    }

    @Test
    fun `a create failure shows its inline error on the create screen`() {
        rule.setContent { StatusScreen(UiState.CreateEvent(error = "Couldn't reach the server."), cutoff = fixedCutoff()) }
        rule.onNodeWithText("Couldn't reach the server.").assertExists()
    }

    @Test
    fun `create is disabled until a name is typed`() {
        rule.setContent { StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff()) }

        rule.onNodeWithText("Create event").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("My Party")
        rule.onNodeWithText("Create event").assertIsEnabled()
    }

    @Test
    fun `tapping create submits the typed name`() {
        var created: String? = null
        rule.setContent { StatusScreen(UiState.CreateEvent(), onCreateEvent = { n, _ -> created = n }, cutoff = fixedCutoff()) }

        rule.onNode(hasSetTextAction()).performTextInput("My Party")
        rule.onNodeWithText("Create event").performClick()
        assertEquals("My Party", created)
    }

    @Test
    fun `the create screen shows the start row defaulting to now`() {
        rule.setContent {
            StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff())
        }
        rule.onNodeWithText("Starts 6 Jul 2026, 12:00").assertExists()
        rule.onNodeWithContentDescription("Edit start date").assertExists()
    }

    @Test
    fun `tapping create submits the typed name AND the chosen start`() {
        var createdName: String? = null
        var createdStart: LocalDateTime? = null
        rule.setContent {
            StatusScreen(
                UiState.CreateEvent(),
                onCreateEvent = { n, s -> createdName = n; createdStart = s },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNode(hasSetTextAction()).performTextInput("My Party")
        rule.onNodeWithText("Create event").performClick()

        assertEquals("My Party", createdName)
        // The default start is "now" — as a LOCAL wall-clock value. The container converts it; the screen
        // never touches a clock, a timezone, or a cutoff string.
        assertEquals(LocalDateTime(2026, 7, 6, 12, 0), createdStart)
    }

    @Test
    fun `the start default is frozen at first composition, not re-derived at submit`() {
        // The label is the screen's whole statement about what will be sent. A start that silently drifted
        // between being displayed and being posted would make the screen lie.
        val clock = MovableClock(Instant.parse("2026-07-06T12:00:00Z"))
        var createdStart: LocalDateTime? = null
        rule.setContent {
            StatusScreen(
                UiState.CreateEvent(),
                onCreateEvent = { _, s -> createdStart = s },
                cutoff = CutoffFormatter(now = clock::now, zone = TimeZone.UTC),
            )
        }
        rule.onNodeWithText("Starts 6 Jul 2026, 12:00").assertExists()

        // Ten minutes pass while the user types.
        clock.instant = Instant.parse("2026-07-06T12:10:00Z")
        rule.onNode(hasSetTextAction()).performTextInput("My Party")
        rule.onNodeWithText("Create event").performClick()

        // The label said 12:00 and 12:00 is what was sent — NOT the instant Create was tapped.
        rule.onNodeWithText("Starts 6 Jul 2026, 12:00").assertExists()
        assertEquals(LocalDateTime(2026, 7, 6, 12, 0), createdStart)
    }

    @Test
    fun `the edit affordance opens ONE dialog showing the calendar and time wheels together`() {
        // The picker is a single dialog: a hand-drawn month calendar AND the HH:MM time wheels visible at
        // once — never the old two-step date -> Next -> time -> OK / Edit-time -> Back mode swap. One OK
        // commits both.
        //
        // Reduce motion is REQUIRED: the picker's time wheels animate on open (a LazyColumn settle), and an
        // animating scene never reaches idle — without this flag `waitForIdle` stalls for ~16 min.
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) { StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff()) }
        }
        rule.onNodeWithText("Date & time").assertDoesNotExist() // no dialog yet

        rule.onNodeWithContentDescription("Edit start date").performClick()

        rule.onNodeWithText("Date & time").assertExists()
        rule.onNodeWithText("OK").assertExists()
        rule.onNodeWithText("Cancel").assertExists()
        // The two-step flow's controls are gone: no Next, no Back, no separate time-edit affordance.
        rule.onNodeWithText("Next").assertDoesNotExist()
        rule.onNodeWithText("Back").assertDoesNotExist()
        rule.onNodeWithContentDescription("Edit time").assertDoesNotExist()
        // Calendar pane present (the visible month) AND the time pane present (both wheels), simultaneously.
        rule.onNodeWithText("July 2026").assertExists()
        rule.onNodeWithContentDescription("Hour", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Minute", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the picker time wheels expose the current start`() {
        // The default start is now — 6 Jul 2026, 12:00 — so the wheels open on 12 and 00. Reduce motion is
        // required so the wheels snap (an animating scene never idles — see the dialog test above).
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) { StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff()) }
        }
        rule.onNodeWithContentDescription("Edit start date").performClick()

        rule.onNodeWithContentDescription("Hour", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "12"))
        rule.onNodeWithContentDescription("Minute", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "00"))
    }

    @Test
    fun `the name field caps at 100 characters`() {
        rule.setContent { StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff()) }

        val field = rule.onNode(hasSetTextAction())
        field.performTextInput("a".repeat(100))
        field.performTextInput("b")
        val text = field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals(100, text.length)
    }

    @Test
    fun `create layer shows no sync line, leave, or invite`() {
        rule.setContent { StatusScreen(UiState.CreateEvent(), inviteUrl = SAMPLE_INVITE, cutoff = fixedCutoff()) }

        rule.onNodeWithText("In sync").assertDoesNotExist()
        rule.onNodeWithText("Synchronization", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Leave event").assertDoesNotExist()
        rule.onNodeWithText("Scan to join this event").assertDoesNotExist()
    }

    @Test
    fun `creating event shows a preparing indicator and no input`() {
        rule.setContent { StatusScreen(UiState.CreatingEvent, cutoff = fixedCutoff()) }

        rule.onNodeWithText("Creating your event …").assertExists()
        rule.onNode(hasAnyProgressIndication()).assertExists()
        rule.onNodeWithText("Event name").assertDoesNotExist()
    }

    // ---- joined layer: status line ----

    @Test
    fun `in sync shows the settled line and no counts`() {
        rule.setContent { StatusScreen(inSync, cutoff = fixedCutoff()) }

        rule.onNodeWithText("In sync").assertExists()
        rule.onNodeWithText("images synced", substring = true).assertDoesNotExist()
    }

    @Test
    fun `syncing with an in-flight arrow reads ongoing`() {
        rule.setContent { StatusScreen(syncing, cutoff = fixedCutoff()) }

        rule.onNodeWithText("Synchronization ongoing…").assertExists()
        rule.onNodeWithText("images synced", substring = true).assertDoesNotExist()
    }

    @Test
    fun `syncing with a static arrow reads pending`() {
        rule.setContent { StatusScreen(syncPending, cutoff = fixedCutoff()) }

        rule.onNodeWithText("Synchronization pending…").assertExists()
        rule.onNodeWithText("Synchronization ongoing…").assertDoesNotExist()
    }

    // ---- reduce motion (capability `design-system`) ----

    /**
     * The requirement is an **absence** — "SHALL respect reduced-motion preferences" — so the test asserts
     * one: render two frames a third of a pulse apart and prove the pixels are identical. That is the
     * property itself, not a proxy for it. The control below is what makes it mean anything.
     */
    @Test
    fun `reduce motion leaves the pulsing arrow un-animated`() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) { StatusScreen(syncing, cutoff = fixedCutoff()) }
        }
        rule.onNodeWithText("Synchronization ongoing…").assertExists()

        val first = rule.onRoot().captureToImage().toPixelMap()
        rule.mainClock.advanceTimeBy(350)
        val second = rule.onRoot().captureToImage().toPixelMap()

        assertTrue(samePixels(first, second), "reduce motion must leave the frame unchanged over time")
    }

    /** The control: without the preference the same state DOES move — or the test above proves nothing. */
    @Test
    fun `without reduce motion the pulsing arrow animates`() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides false) { StatusScreen(syncing, cutoff = fixedCutoff()) }
        }
        rule.onNodeWithText("Synchronization ongoing…").assertExists()

        val first = rule.onRoot().captureToImage().toPixelMap()
        rule.mainClock.advanceTimeBy(350) // half the 700ms fade — the alpha cannot be back where it started
        val second = rule.onRoot().captureToImage().toPixelMap()

        assertFalse(samePixels(first, second), "a pulsing arrow animates when motion is allowed")
    }

    /** Reduce motion changes no meaning: the label still distinguishes in-flight from merely pending. */
    @Test
    fun `reduce motion keeps the ongoing-vs-pending distinction`() {
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) { StatusScreen(syncPending, cutoff = fixedCutoff()) }
        }

        rule.onNodeWithText("Synchronization pending…").assertExists()
        rule.onNodeWithText("Synchronization ongoing…").assertDoesNotExist()
    }

    @Test
    fun `needs-access not-determined shows the allow copy and taps request permission`() {
        var requests = 0
        rule.setContent {
            StatusScreen(
                joined(SyncHealth.NeedsAccess(PermissionStatus.NOT_DETERMINED)),
                onRequestPermission = { requests++ },
             cutoff = fixedCutoff())
        }

        rule.onNodeWithText("Allow photo access").assertExists()
        rule.onNodeWithText("Allow photo access").performClick()
        assertEquals(1, requests)
    }

    @Test
    fun `needs-access denied shows the settings copy and taps open settings`() {
        var settingsOpens = 0
        rule.setContent {
            StatusScreen(
                joined(SyncHealth.NeedsAccess(PermissionStatus.DENIED)),
                onOpenSettings = { settingsOpens++ },
             cutoff = fixedCutoff())
        }

        rule.onNodeWithText("Turn on full access in Settings").assertExists()
        rule.onNodeWithText("Turn on full access in Settings").performClick()
        assertEquals(1, settingsOpens)
    }

    // ---- joined layer: partial-grant resting affordances (capability `limited-photo-access`) ----

    @Test
    fun `limited grant shows both affordances in order, in every health`() {
        // One recomposing scene walks the healths — the affordances are resting offers, present
        // regardless of the current health value, with the grant switch always BELOW the selection
        // widening (the cheaper step leads).
        val state = mutableStateOf<UiState>(UiState.Joined(SyncHealth.InSync, canChoosePhotos = true))
        rule.setContent { StatusScreen(state.value, cutoff = fixedCutoff()) }

        val healths = listOf(
            SyncHealth.InSync,
            SyncHealth.Syncing(Arrow.PULSING, Arrow.HIDDEN),
            SyncHealth.NotStarted("2026-07-04T18:00:00Z"),
        )
        for (health in healths) {
            state.value = UiState.Joined(health, canChoosePhotos = true)
            rule.waitForIdle()
            val chooseY = rule.onNodeWithText("Choose more photos").fetchSemanticsNode().positionInRoot.y
            val allowY = rule.onNodeWithText("Allow full access").fetchSemanticsNode().positionInRoot.y
            assertTrue(allowY > chooseY, "Allow full access must sit below Choose more photos ($health)")
        }
    }

    @Test
    fun `allow full access taps open settings and nothing else`() {
        var settingsOpens = 0
        var pickerOpens = 0
        var requests = 0
        rule.setContent {
            StatusScreen(
                UiState.Joined(SyncHealth.InSync, canChoosePhotos = true),
                onOpenSettings = { settingsOpens++ },
                onChoosePhotos = { pickerOpens++ },
                onRequestPermission = { requests++ },
                cutoff = fixedCutoff(),
            )
        }

        rule.onNodeWithText("Allow full access").performClick()
        assertEquals(1, settingsOpens)
        assertEquals(0, pickerOpens)
        assertEquals(0, requests)
    }

    @Test
    fun `choose more photos taps the picker callback, not settings`() {
        var settingsOpens = 0
        var pickerOpens = 0
        rule.setContent {
            StatusScreen(
                UiState.Joined(SyncHealth.InSync, canChoosePhotos = true),
                onOpenSettings = { settingsOpens++ },
                onChoosePhotos = { pickerOpens++ },
                cutoff = fixedCutoff(),
            )
        }

        rule.onNodeWithText("Choose more photos").performClick()
        assertEquals(1, pickerOpens)
        assertEquals(0, settingsOpens)
    }

    @Test
    fun `no partial-grant affordances under a full grant`() {
        // canChoosePhotos defaults false (permission != LIMITED) — neither offer renders.
        rule.setContent { StatusScreen(inSync, cutoff = fixedCutoff()) }

        rule.onNodeWithText("Choose more photos").assertDoesNotExist()
        rule.onNodeWithText("Allow full access").assertDoesNotExist()
    }

    // ---- joined layer: name, leave, invite ----

    @Test
    fun `joined shows the event name as the title`() {
        rule.setContent { StatusScreen(inSync, eventName = "Anna's Birthday", cutoff = fixedCutoff()) }
        rule.onNodeWithText("Anna's Birthday").assertExists()
    }

    @Test
    fun `joined shows the leave action`() {
        rule.setContent { StatusScreen(inSync, cutoff = fixedCutoff()) }
        rule.onNodeWithContentDescription("Leave event").assertExists()
    }

    @Test
    fun `needs-access still shows leave and invite (sharing needs no access)`() {
        rule.setContent {
            StatusScreen(
                joined(SyncHealth.NeedsAccess(PermissionStatus.DENIED)),
                inviteUrl = SAMPLE_INVITE,
             cutoff = fixedCutoff())
        }
        rule.onNodeWithContentDescription("Leave event").assertExists()
        rule.onNodeWithText("Scan to join this event").assertExists()
        rule.onNodeWithContentDescription("Share invite link").assertExists()
    }

    @Test
    fun `activating leave shows the leave-this-event dialog`() {
        rule.setContent { StatusScreen(inSync, cutoff = fixedCutoff()) }

        rule.onNodeWithText("Leave this event?").assertDoesNotExist()
        rule.onNodeWithContentDescription("Leave event").performClick()
        rule.onNodeWithText("Leave this event?").assertExists()
    }

    @Test
    fun `confirming leave invokes the callback`() {
        var leaves = 0
        rule.setContent { StatusScreen(inSync, onLeaveEvent = { leaves++ }, cutoff = fixedCutoff()) }

        rule.onNodeWithContentDescription("Leave event").performClick()
        rule.onNodeWithText("Leave").performClick()
        assertEquals(1, leaves)
    }

    @Test
    fun `staying does not invoke leave and dismisses the dialog`() {
        var leaves = 0
        rule.setContent { StatusScreen(inSync, onLeaveEvent = { leaves++ }, cutoff = fixedCutoff()) }

        rule.onNodeWithContentDescription("Leave event").performClick()
        rule.onNodeWithText("Stay").performClick()
        assertEquals(0, leaves)
        rule.onNodeWithText("Leave this event?").assertDoesNotExist()
    }

    @Test
    fun `joined shows the invite QR and share action`() {
        rule.setContent { StatusScreen(inSync, inviteUrl = SAMPLE_INVITE, cutoff = fixedCutoff()) }
        rule.onNodeWithText("Scan to join this event").assertExists()
        rule.onNodeWithContentDescription("Share invite link").assertExists()
    }

    @Test
    fun `joined without an invite url hides the invite affordances`() {
        rule.setContent { StatusScreen(inSync, inviteUrl = null, cutoff = fixedCutoff()) }
        rule.onNodeWithText("Scan to join this event").assertDoesNotExist()
        rule.onNodeWithContentDescription("Share invite link").assertDoesNotExist()
    }

    @Test
    fun `activating share invokes the callback`() {
        var shares = 0
        rule.setContent { StatusScreen(inSync, onShareInvite = { shares++ }, inviteUrl = SAMPLE_INVITE, cutoff = fixedCutoff()) }
        rule.onNodeWithContentDescription("Share invite link").performClick()
        assertEquals(1, shares)
    }

    // ---- the settings action + reconfigure surface (capability `reconfigure-membership`) ----

    @Test
    fun `joined with a membership shows the settings action next to share and leave`() {
        rule.setContent { StatusScreen(inSync, membership = MEMBERSHIP, inviteUrl = SAMPLE_INVITE, cutoff = fixedCutoff()) }
        rule.onNodeWithContentDescription("Event settings").assertExists()
        rule.onNodeWithContentDescription("Share invite link").assertExists()
        rule.onNodeWithContentDescription("Leave event").assertExists()
    }

    @Test
    fun `the settings action is present under needs-access (no photo access required)`() {
        rule.setContent {
            StatusScreen(
                joined(SyncHealth.NeedsAccess(PermissionStatus.DENIED)),
                membership = MEMBERSHIP,
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithContentDescription("Event settings").assertExists()
    }

    @Test
    fun `the settings action is suppressed during a pending switch`() {
        rule.setContent {
            StatusScreen(
                UiState.Joined(
                    SyncHealth.InSync,
                    PendingSwitch("22222222-2222-4222-8222-222222222222", JoinPhase.Ready("New Event", "2026-07-06T00:00:00Z")),
                ),
                membership = MEMBERSHIP,
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithContentDescription("Event settings").assertDoesNotExist()
    }

    @Test
    fun `tapping settings opens the reconfigure surface`() {
        rule.setContent { StatusScreen(inSync, membership = MEMBERSHIP, cutoff = fixedCutoff()) }
        rule.onNodeWithText("Save").assertDoesNotExist()
        rule.onNodeWithContentDescription("Event settings").performClick()
        rule.onNodeWithText("Save").assertExists()
        rule.onNodeWithText("Share my photos").assertExists()
        rule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun `the reconfigure surface seeds the Event-start cutoff when the cutoff is at the floor`() {
        // minPhotoDate == startsAt → Event-start preset → resulting instant is the start.
        rule.setContent { StatusScreen(inSync, membership = MEMBERSHIP, cutoff = fixedCutoff()) }
        rule.onNodeWithContentDescription("Event settings").performClick()
        rule.onNodeWithText("Shared from 6 Jul 2026, 12:00").assertExists()
    }

    @Test
    fun `the reconfigure surface seeds a Custom cutoff when the cutoff is above the floor`() {
        val above = MEMBERSHIP.copy(minPhotoDate = "2026-07-06T18:00:00Z")
        rule.setContent { StatusScreen(inSync, membership = above, cutoff = fixedCutoff()) }
        rule.onNodeWithContentDescription("Event settings").performClick()
        rule.onNodeWithText("Shared from 6 Jul 2026, 18:00").assertExists()
    }

    @Test
    fun `turning the album on shows the forward-only helper text`() {
        val withAlbum = MEMBERSHIP.copy(saveToAlbum = true)
        rule.setContent { StatusScreen(inSync, membership = withAlbum, cutoff = fixedCutoff()) }
        rule.onNodeWithContentDescription("Event settings").performClick()
        rule.onNodeWithText("Only photos synced from now on are added.", substring = true).assertExists()
    }

    @Test
    fun `saving invokes the reconfigure callback with the membership's values and closes the surface`() {
        var savedEventId: String? = null
        var savedDirection: Direction? = null
        var savedCutoff: String? = null
        var savedAlbum: Boolean? = null
        rule.setContent {
            StatusScreen(
                inSync,
                membership = MEMBERSHIP,
                onReconfigure = { e, d, c, a -> savedEventId = e; savedDirection = d; savedCutoff = c; savedAlbum = a },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNodeWithContentDescription("Event settings").performClick()
        rule.onNodeWithText("Save").performClick()

        assertEquals("E1", savedEventId)
        assertEquals(Direction.Both, savedDirection)
        assertEquals("2026-07-06T12:00:00Z", savedCutoff)
        assertEquals(false, savedAlbum)
        // Save closes the surface (back to the joined layer's action row).
        rule.onNodeWithText("Save").assertDoesNotExist()
        rule.onNodeWithContentDescription("Event settings").assertExists()
    }

    private fun hasAnyProgressIndication(): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
}

// A representative joined membership for the reconfigure-surface tests: event started, cutoff at the
// floor, bidirectional, no album — so a no-edit Save round-trips these exact values.
private val MEMBERSHIP = EventConfig(
    eventId = "E1",
    name = "Anna's Birthday",
    minPhotoDate = "2026-07-06T12:00:00Z",
    startsAt = "2026-07-06T12:00:00Z",
    direction = Direction.Both,
    saveToAlbum = false,
)

private fun samePixels(a: PixelMap, b: PixelMap): Boolean {
    if (a.width != b.width || a.height != b.height) return false
    for (y in 0 until a.height) for (x in 0 until a.width) if (a[x, y] != b[x, y]) return false
    return true
}
