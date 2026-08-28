@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.ui

import app.snapsync.model.eventEnd
import app.snapsync.model.deletesAt
import app.snapsync.model.captureCutoff
import app.snapsync.model.captureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.eventStart
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.snapsync.model.PermissionStatus
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.ui.components.LocalReduceMotion
import app.snapsync.model.Arrow
import app.snapsync.presentation.RangeForm
import app.snapsync.presentation.ResolvedRange
import app.snapsync.presentation.JoinedSurface
import app.snapsync.model.FromChoice
import app.snapsync.model.UntilChoice
import app.snapsync.presentation.Overlays
import app.snapsync.presentation.RenameState
import app.snapsync.presentation.Layer
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.EventDetails
import app.snapsync.model.EventStart
import app.snapsync.model.EventEnd
import app.snapsync.model.DeletesAt
import app.snapsync.presentation.JoinPhase
import app.snapsync.presentation.PendingSwitch
import app.snapsync.feature.membership.RenameFailureReason
import app.snapsync.feature.membership.RenameStatus
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
import app.snapsync.ui.JoinedActions
import app.snapsync.ui.AccessActions

// A representative invite link — any string renders a QR; the encoding is pinned in capability:config.
private const val SAMPLE_INVITE = "https://snapsync.stho.net/join#v=3&d=eyJldmVudElkIjoiMSJ9"

// A representative joined membership for the reconfigure-surface tests: event started, the full window
// `[startsAt, endsAt]` at both bounds (floor + ceiling), bidirectional, no album — so a no-edit Save
// round-trips these exact values.
private val MEMBERSHIP = EventConfig(
    eventId = "E1",
    name = "Anna's Birthday",
    minPhotoDate = captureCutoff("2026-07-06T12:00:00Z"),
    startsAt = eventStart("2026-07-06T12:00:00Z"),
    endsAt = eventEnd("2026-07-10T12:00:00Z"),
    maxPhotoDate = captureCeiling("2026-07-10T12:00:00Z"),
    direction = Direction.Both,
    saveToAlbum = false,
)

/** The joined layer with the leave confirmation up — the state the leave action produces. */
private fun confirmingLeave() = inSync.copy(overlays = Overlays(confirmingLeave = true))

/** The joined layer with the rename sheet up, optionally carrying a rename outcome. */
private fun renaming(renameState: RenameState = RenameState.Idle) =
    joined(SyncHealth.InSync, renameState = renameState).copy(overlays = Overlays(renaming = true))

/**
 * The joined layer showing its SETTINGS surface, with [form] already seeded and resolved.
 *
 * Opening the surface is an intent now, so a screen test cannot tap its way in — it supplies the state
 * the tap produces and asserts what is drawn. Whether the seed RECONSTRUCTS the right presets from a
 * persisted membership is `reconfigureForm`'s answer, checked in `RangeResolutionTest`.
 */
private fun reconfiguring(
    membership: EventConfig = MEMBERSHIP,
    form: RangeForm = RangeForm(),
) = UiState(
    Layer.Joined(
        membership = membership,
        inviteUrl = SAMPLE_INVITE,
        health = SyncHealth.InSync,
        surface = JoinedSurface.Reconfigure(form, reconfigureResolved(membership, form)),
    ),
)

/** What the reduction would resolve [form] to against [membership]'s own window — stated, not re-derived. */
private fun reconfigureResolved(membership: EventConfig, form: RangeForm): ResolvedRange {
    val f = fixedCutoff()
    val windowStart = f.toLocal(membership.startsAt.at)!!
    val windowEnd = f.toLocal(membership.endsAt?.at ?: membership.maxPhotoDate.at)!!
    val from = if (form.fromPreset == FromChoice.CUSTOM) form.fromCustom ?: windowStart else windowStart
    val until = if (form.untilPreset == UntilChoice.CUSTOM) form.untilCustom ?: windowEnd else windowEnd
    return ResolvedRange(
        windowStart = windowStart,
        windowEnd = windowEnd,
        from = from,
        until = until,
        chosenFrom = CaptureCutoff(f.toCutoff(from)),
        chosenUntil = CaptureCeiling(f.toCutoff(until)),
        direction = if (form.shareOn && form.receiveOn) Direction.Both else Direction.DownloadOnly,
        commitEnabled = form.shareOn || form.receiveOn,
        nowAvailable = true,
    )
}

private fun joined(
    health: SyncHealth,
    pendingSwitch: PendingSwitch? = null,
    canChoosePhotos: Boolean = false,
    ended: Boolean = false,
    membership: EventConfig = MEMBERSHIP,
    inviteUrl: String = SAMPLE_INVITE,
    renameState: RenameState = RenameState.Idle,
) = UiState(Layer.Joined(membership, inviteUrl, health, pendingSwitch, canChoosePhotos, ended, renameState))
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

    // ---- the not-started clock line ----

    @Test
    fun `the not-started health renders a clock line naming the start — below the QR`() = runComposeUiTest {
        setContent {
            StatusScreen(
                joined(SyncHealth.NotStarted(eventStart("2026-07-04T18:00:00Z"))),
                cutoff = fixedCutoff(),
            )
        }
        // Rendered in the DEVICE's local zone (UTC here), in the same one-line slot every other status
        // uses — the joined layer never grows a second line.
        onNodeWithText("Starts 4 Jul, 18:00").assertExists()
        // It is information, not an action: no sync arrows, no "In sync".
        onNodeWithText("In sync").assertDoesNotExist()
        onNodeWithText("Synchronization pending …").assertDoesNotExist()
    }

    // ---- create layer ----

    @Test
    fun `create screen shows the name input and the scan-to-join hint`() = runComposeUiTest {
        setContent { StatusScreen(UiState(Layer.CreateEvent()), cutoff = fixedCutoff()) }

        onNodeWithText("Start an event").assertExists()
        onNodeWithText("Or scan a QR code in the Camera app to join one.").assertExists()
        onNodeWithText("Event name").assertExists()
        onNodeWithText("Create event").assertExists()
    }

    @Test
    fun `invalid deeplink error shows on the create screen`() = runComposeUiTest {
        setContent {
            StatusScreen(UiState(Layer.CreateEvent(error = "That QR code wasn't valid.")), cutoff = fixedCutoff())
        }
        onNodeWithText("That QR code wasn't valid.").assertExists()
    }

    @Test
    fun `a create failure shows its inline error on the create screen`() = runComposeUiTest {
        setContent { StatusScreen(UiState(Layer.CreateEvent(error = "Couldn't reach the server.")), cutoff = fixedCutoff()) }
        onNodeWithText("Couldn't reach the server.").assertExists()
    }

    @Test
    fun `create is disabled until a name is typed`() = runComposeUiTest {
        setContent { StatusScreen(UiState(Layer.CreateEvent()), cutoff = fixedCutoff()) }

        onNodeWithText("Create event").assertIsNotEnabled()
        onNode(hasSetTextAction()).performTextInput("My Party")
        onNodeWithText("Create event").assertIsEnabled()
    }

    @Test
    fun `tapping create submits the typed name`() = runComposeUiTest {
        var created: String? = null
        setContent { StatusScreen(UiState(Layer.CreateEvent()), cutoff = fixedCutoff(), actions = StatusActions(onCreateEvent = { n, _, _ -> created = n })) }

        onNode(hasSetTextAction()).performTextInput("My Party")
        onNodeWithText("Create event").performClick()
        assertEquals("My Party", created)
    }

    @Test
    fun `the create screen shows the date range defaulting to now to now plus 1d with a duration hint`() = runComposeUiTest {
        setContent {
            StatusScreen(UiState(Layer.CreateEvent()), cutoff = fixedCutoff())
        }
        // now = 6 Jul 12:00 (UTC) → default window [6 Jul 12:00, 7 Jul 12:00], the compact adaptive label.
        onNodeWithText("6 Jul 12:00 – 7 Jul 12:00").assertExists()
        onNodeWithText("Event lasts 1 day").assertExists()
        onNodeWithContentDescription("Edit event dates").assertExists()
    }

    @Test
    fun `tapping create submits the typed name AND the chosen date range`() = runComposeUiTest {
        var createdName: String? = null
        var createdFrom: LocalDateTime? = null
        var createdUntil: LocalDateTime? = null
        setContent {
            StatusScreen(
                UiState(Layer.CreateEvent()),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    onCreateEvent = { n, f, u -> createdName = n; createdFrom = f; createdUntil = u },
                )
            )
        }
        onNode(hasSetTextAction()).performTextInput("My Party")
        onNodeWithText("Create event").performClick()

        assertEquals("My Party", createdName)
        // The default window is [now, now + 1 day] as LOCAL wall-clock values. The container converts each;
        // the screen never touches a clock, a timezone, or a cutoff string.
        assertEquals(LocalDateTime(2026, 7, 6, 12, 0), createdFrom)
        assertEquals(LocalDateTime(2026, 7, 7, 12, 0), createdUntil)
    }

    @Test
    fun `the range default is frozen at first composition and not re-derived at submit`() = runComposeUiTest {
        // The label is the screen's whole statement about what will be sent. A range that silently drifted
        // between being displayed and being posted would make the screen lie.
        val clock = MovableClock(Instant.parse("2026-07-06T12:00:00Z"))
        var createdFrom: LocalDateTime? = null
        setContent {
            StatusScreen(
                UiState(Layer.CreateEvent()),
                cutoff = CutoffFormatter(now = clock::now, zone = TimeZone.UTC),
                actions = StatusActions(
                    onCreateEvent = { _, f, _ -> createdFrom = f },
                )
            )
        }
        onNodeWithText("6 Jul 12:00 – 7 Jul 12:00").assertExists()

        // Ten minutes pass while the user types.
        clock.instant = Instant.parse("2026-07-06T12:10:00Z")
        onNode(hasSetTextAction()).performTextInput("My Party")
        onNodeWithText("Create event").performClick()

        // The label said 12:00 and 12:00 is what was sent — NOT the instant Create was tapped.
        onNodeWithText("6 Jul 12:00 – 7 Jul 12:00").assertExists()
        assertEquals(LocalDateTime(2026, 7, 6, 12, 0), createdFrom)
    }

    @Test
    fun `the edit affordance opens ONE range dialog showing the calendar and both time wheels together`() = runComposeUiTest {
        // The picker is a single dialog: a hand-drawn month calendar AND both HH:MM time-wheel pairs (From
        // and Until) visible at once. One OK commits the whole span.
        //
        // Reduce motion is REQUIRED: the picker's time wheels animate on open (a LazyColumn settle), and an
        // animating scene never reaches idle — without this flag `waitForIdle` stalls for ~16 min.
        setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) { StatusScreen(UiState(Layer.CreateEvent()), cutoff = fixedCutoff()) }
        }
        onNodeWithText("Date & time").assertDoesNotExist() // no dialog yet

        onNodeWithContentDescription("Edit event dates").performClick()

        onNodeWithText("Date & time").assertExists()
        onNodeWithText("OK").assertExists()
        onNodeWithText("Cancel").assertExists()
        // Calendar pane present (the visible month) AND both time panes present (all four wheels).
        onNodeWithText("July 2026").assertExists()
        onNodeWithContentDescription("From hour", useUnmergedTree = true).assertExists()
        onNodeWithContentDescription("From minute", useUnmergedTree = true).assertExists()
        onNodeWithContentDescription("Until hour", useUnmergedTree = true).assertExists()
        onNodeWithContentDescription("Until minute", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the range picker time wheels expose the current window bounds`() = runComposeUiTest {
        // The default window is [6 Jul 12:00, 7 Jul 12:00], so both wheel pairs open on 12 and 00. Reduce
        // motion is required so the wheels snap (an animating scene never idles — see the dialog test above).
        setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) { StatusScreen(UiState(Layer.CreateEvent()), cutoff = fixedCutoff()) }
        }
        onNodeWithContentDescription("Edit event dates").performClick()

        onNodeWithContentDescription("From hour", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "12"))
        onNodeWithContentDescription("Until hour", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "12"))
        onNodeWithContentDescription("From minute", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "00"))
    }

    @Test
    fun `the name field caps at 100 characters`() = runComposeUiTest {
        setContent { StatusScreen(UiState(Layer.CreateEvent()), cutoff = fixedCutoff()) }

        val field = onNode(hasSetTextAction())
        field.performTextInput("a".repeat(100))
        field.performTextInput("b")
        val text = field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals(100, text.length)
    }

    @Test
    fun `create layer shows no sync line and no leave and no invite`() = runComposeUiTest {
        setContent { StatusScreen(UiState(Layer.CreateEvent()), cutoff = fixedCutoff()) }

        onNodeWithText("In sync").assertDoesNotExist()
        onNodeWithText("Synchronization", substring = true).assertDoesNotExist()
        onNodeWithContentDescription("Leave event").assertDoesNotExist()
        onNodeWithText("Scan to join this event").assertDoesNotExist()
    }

    @Test
    fun `creating event shows a preparing indicator and no input`() = runComposeUiTest {
        setContent { StatusScreen(UiState(Layer.CreatingEvent), cutoff = fixedCutoff()) }

        onNodeWithText("Creating your event …").assertExists()
        onNode(hasAnyProgressIndication()).assertExists()
        onNodeWithText("Event name").assertDoesNotExist()
    }

    // ---- joined layer: status line ----

    @Test
    fun `in sync shows the settled line and no counts`() = runComposeUiTest {
        setContent { StatusScreen(inSync, cutoff = fixedCutoff()) }

        onNodeWithText("In sync").assertExists()
        onNodeWithText("images synced", substring = true).assertDoesNotExist()
    }

    @Test
    fun `syncing with an in-flight arrow reads ongoing`() = runComposeUiTest {
        setContent { StatusScreen(syncing, cutoff = fixedCutoff()) }

        onNodeWithText("Synchronization ongoing…").assertExists()
        onNodeWithText("images synced", substring = true).assertDoesNotExist()
    }

    @Test
    fun `syncing with a static arrow reads pending`() = runComposeUiTest {
        setContent { StatusScreen(syncPending, cutoff = fixedCutoff()) }

        onNodeWithText("Synchronization pending…").assertExists()
        onNodeWithText("Synchronization ongoing…").assertDoesNotExist()
    }

    // ---- reduce motion (capability `design-system`) ----

    /**
     * The requirement is an **absence** — "SHALL respect reduced-motion preferences" — so the test asserts
     * one: render two frames a third of a pulse apart and prove the pixels are identical. That is the
     * property itself, not a proxy for it. The control below is what makes it mean anything.
     */
    @Test
    fun `reduce motion leaves the pulsing arrow un-animated`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) { StatusScreen(syncing, cutoff = fixedCutoff()) }
        }
        onNodeWithText("Synchronization ongoing…").assertExists()

        val first = onRoot().captureToImage().toPixelMap()
        mainClock.advanceTimeBy(350)
        val second = onRoot().captureToImage().toPixelMap()

        assertTrue(samePixels(first, second), "reduce motion must leave the frame unchanged over time")
    }

    /** The control: without the preference the same state DOES move — or the test above proves nothing. */
    @Test
    fun `without reduce motion the pulsing arrow animates`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalReduceMotion provides false) { StatusScreen(syncing, cutoff = fixedCutoff()) }
        }
        onNodeWithText("Synchronization ongoing…").assertExists()

        val first = onRoot().captureToImage().toPixelMap()
        mainClock.advanceTimeBy(350) // half the 700ms fade — the alpha cannot be back where it started
        val second = onRoot().captureToImage().toPixelMap()

        assertFalse(samePixels(first, second), "a pulsing arrow animates when motion is allowed")
    }

    /** Reduce motion changes no meaning: the label still distinguishes in-flight from merely pending. */
    @Test
    fun `reduce motion keeps the ongoing-vs-pending distinction`() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) { StatusScreen(syncPending, cutoff = fixedCutoff()) }
        }

        onNodeWithText("Synchronization pending…").assertExists()
        onNodeWithText("Synchronization ongoing…").assertDoesNotExist()
    }

    @Test
    fun `needs-access not-determined shows the allow copy and taps request permission`() = runComposeUiTest {
        var requests = 0
        setContent {
            StatusScreen(
                joined(SyncHealth.NeedsAccess(PermissionStatus.NOT_DETERMINED)),
                actions = StatusActions(
                    access = AccessActions(
                        onRequestPermission = { requests++ },
                    ),
                ),
             cutoff = fixedCutoff())
        }

        onNodeWithText("Allow photo access").assertExists()
        onNodeWithText("Allow photo access").performClick()
        assertEquals(1, requests)
    }

    @Test
    fun `needs-access denied shows the settings copy and taps open settings`() = runComposeUiTest {
        var settingsOpens = 0
        setContent {
            StatusScreen(
                joined(SyncHealth.NeedsAccess(PermissionStatus.DENIED)),
                actions = StatusActions(
                    access = AccessActions(
                        onOpenSettings = { settingsOpens++ },
                    ),
                ),
             cutoff = fixedCutoff())
        }

        onNodeWithText("Turn on full access in Settings").assertExists()
        onNodeWithText("Turn on full access in Settings").performClick()
        assertEquals(1, settingsOpens)
    }

    // ---- joined layer: partial-grant resting affordances (capability `limited-photo-access`) ----

    @Test
    fun `limited grant shows both affordances in order — in every health`() = runComposeUiTest {
        // One recomposing scene walks the healths — the affordances are resting offers, present
        // regardless of the current health value, with the grant switch always BELOW the selection
        // widening (the cheaper step leads).
        val state = mutableStateOf<UiState>(joined(SyncHealth.InSync, canChoosePhotos = true))
        setContent { StatusScreen(state.value, cutoff = fixedCutoff()) }

        val healths = listOf(
            SyncHealth.InSync,
            SyncHealth.Syncing(Arrow.PULSING, Arrow.HIDDEN),
            SyncHealth.NotStarted(eventStart("2026-07-04T18:00:00Z")),
        )
        for (health in healths) {
            state.value = joined(health, canChoosePhotos = true)
            waitForIdle()
            val chooseY = onNodeWithText("Choose more photos").fetchSemanticsNode().positionInRoot.y
            val allowY = onNodeWithText("Allow full access").fetchSemanticsNode().positionInRoot.y
            assertTrue(allowY > chooseY, "Allow full access must sit below Choose more photos ($health)")
        }
    }

    @Test
    fun `allow full access taps open settings and nothing else`() = runComposeUiTest {
        var settingsOpens = 0
        var pickerOpens = 0
        var requests = 0
        setContent {
            StatusScreen(
                joined(SyncHealth.InSync, canChoosePhotos = true),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    access = AccessActions(
                        onOpenSettings = { settingsOpens++ },
                        onChoosePhotos = { pickerOpens++ },
                        onRequestPermission = { requests++ },
                    ),
                )
            )
        }

        onNodeWithText("Allow full access").performClick()
        assertEquals(1, settingsOpens)
        assertEquals(0, pickerOpens)
        assertEquals(0, requests)
    }

    @Test
    fun `choose more photos taps the picker callback and not settings`() = runComposeUiTest {
        var settingsOpens = 0
        var pickerOpens = 0
        setContent {
            StatusScreen(
                joined(SyncHealth.InSync, canChoosePhotos = true),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    access = AccessActions(
                        onOpenSettings = { settingsOpens++ },
                        onChoosePhotos = { pickerOpens++ },
                    ),
                )
            )
        }

        onNodeWithText("Choose more photos").performClick()
        assertEquals(1, pickerOpens)
        assertEquals(0, settingsOpens)
    }

    @Test
    fun `no partial-grant affordances under a full grant`() = runComposeUiTest {
        // canChoosePhotos defaults false (permission != LIMITED) — neither offer renders.
        setContent { StatusScreen(inSync, cutoff = fixedCutoff()) }

        onNodeWithText("Choose more photos").assertDoesNotExist()
        onNodeWithText("Allow full access").assertDoesNotExist()
    }

    // ---- joined layer: name, leave, invite ----

    @Test
    fun `joined shows the event name as the title`() = runComposeUiTest {
        setContent { StatusScreen(inSync, cutoff = fixedCutoff()) }
        onNodeWithText("Anna's Birthday").assertExists()
    }

    @Test
    fun `joined shows the leave action`() = runComposeUiTest {
        setContent { StatusScreen(inSync, cutoff = fixedCutoff()) }
        onNodeWithContentDescription("Leave event").assertExists()
    }

    @Test
    fun `needs-access still shows leave and invite — sharing needs no access`() = runComposeUiTest {
        setContent {
            StatusScreen(
                joined(SyncHealth.NeedsAccess(PermissionStatus.DENIED)),
             cutoff = fixedCutoff())
        }
        onNodeWithContentDescription("Leave event").assertExists()
        onNodeWithText("Scan to join this event").assertExists()
        onNodeWithContentDescription("Share invite link").assertExists()
    }

    @Test
    fun `activating leave asks for the confirmation`() = runComposeUiTest {
        var asked = 0
        setContent {
            StatusScreen(
                inSync,
                cutoff = fixedCutoff(),
                actions = StatusActions(surfaces = SurfaceActions(onConfirmLeaveOpen = { asked++ })),
            )
        }
        onNodeWithText("Leave this event?").assertDoesNotExist()
        onNodeWithContentDescription("Leave event").performClick()
        assertEquals(1, asked)
    }

    @Test
    fun `the leave confirmation renders when the state says it is up`() = runComposeUiTest {
        setContent { StatusScreen(confirmingLeave(), cutoff = fixedCutoff()) }
        onNodeWithText("Leave this event?").assertExists()
    }

    @Test
    fun `confirming leave invokes the callback`() = runComposeUiTest {
        var leaves = 0
        setContent {
            StatusScreen(
                confirmingLeave(),
                cutoff = fixedCutoff(),
                actions = StatusActions(joined = JoinedActions(onLeaveEvent = { leaves++ })),
            )
        }

        onNodeWithText("Leave").performClick()
        assertEquals(1, leaves)
    }

    @Test
    fun `staying does not invoke leave and asks for the dialog to close`() = runComposeUiTest {
        var leaves = 0
        var dismissed = 0
        setContent {
            StatusScreen(
                confirmingLeave(),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    joined = JoinedActions(onLeaveEvent = { leaves++ }),
                    surfaces = SurfaceActions(onConfirmLeaveDismiss = { dismissed++ }),
                ),
            )
        }
        onNodeWithText("Stay").performClick()
        assertEquals(0, leaves)
        assertEquals(1, dismissed)
    }

    @Test
    fun `joined shows the invite QR and share action`() = runComposeUiTest {
        setContent { StatusScreen(inSync, cutoff = fixedCutoff()) }
        onNodeWithText("Scan to join this event").assertExists()
        onNodeWithContentDescription("Share invite link").assertExists()
    }

    @Test
    fun `activating share invokes the callback`() = runComposeUiTest {
        var shares = 0
        setContent { StatusScreen(inSync, cutoff = fixedCutoff(), actions = StatusActions(joined = JoinedActions(onShareInvite = { shares++ }))) }
        onNodeWithContentDescription("Share invite link").performClick()
        assertEquals(1, shares)
    }

    // ---- the rename affordance + dialog (capability `event-rename`) ----

    @Test
    fun `joined with a membership shows the rename pen beside the heading`() = runComposeUiTest {
        setContent {
            StatusScreen(inSync, cutoff = fixedCutoff())
        }
        onNodeWithText("Anna's Birthday").assertExists()
        onNodeWithContentDescription("Rename event").assertExists()
    }

    @Test
    fun `the rename pen is present in every joined health — including without photo access`() = runComposeUiTest {
        // Renaming needs neither photo access nor a started event, so no health value may hide it.
        val health = mutableStateOf<SyncHealth>(SyncHealth.InSync)
        setContent {
            StatusScreen(
                joined(health.value),
                cutoff = fixedCutoff(),
            )
        }
        for (value in listOf(
            SyncHealth.InSync,
            SyncHealth.Syncing(Arrow.PULSING, Arrow.HIDDEN),
            SyncHealth.NeedsAccess(PermissionStatus.DENIED),
            SyncHealth.NeedsAccess(PermissionStatus.NOT_DETERMINED),
        )) {
            health.value = value
            waitForIdle()
            onNodeWithContentDescription("Rename event").assertExists()
        }
    }

    @Test
    fun `the rename pen stays offered while a pending switch is carried`() = runComposeUiTest {
        // It used to be suppressed here. `RenameEvent` guards the `eventId` itself, so a rename landing
        // across a switch renames nothing; hiding the control bought nothing, and it cost the pen for the
        // whole of every join's commit, which carries a pending join for the event being joined.
        setContent {
            StatusScreen(
                joined(
                    SyncHealth.InSync,
                    PendingSwitch(
                        "22222222-2222-4222-8222-222222222222",
                        phaseAt(JoinPhase.Detailed.Step.Ready, 
                            "New Event",
                            eventStart("2026-07-06T00:00:00Z"),
                            eventEnd("2026-07-16T00:00:00Z"),
                            deletesAt("2026-08-05T00:00:00Z"),
                        ),
                    ),
                ),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithContentDescription("Rename event").assertExists()
    }

    @Test
    fun `the rename pen is absent on the create screen — there is no heading to rename`() = runComposeUiTest {
        setContent { StatusScreen(UiState(Layer.CreateEvent()), cutoff = fixedCutoff()) }
        onNodeWithContentDescription("Rename event").assertDoesNotExist()
    }

    @Test
    fun `the rename pen is absent while the reconfigure surface is open`() = runComposeUiTest {
        setContent { StatusScreen(reconfiguring(), cutoff = fixedCutoff()) }
        onNodeWithContentDescription("Rename event").assertDoesNotExist()
    }

    @Test
    fun `the pen asks for the rename dialog`() = runComposeUiTest {
        var asked = 0
        setContent {
            StatusScreen(
                inSync,
                cutoff = fixedCutoff(),
                actions = StatusActions(surfaces = SurfaceActions(onRenameOpen = { asked++ })),
            )
        }
        onNodeWithContentDescription("Rename event").performClick()
        assertEquals(1, asked)
    }

    @Test
    fun `the rename dialog opens PRE-FILLED with the current name`() = runComposeUiTest {
        setContent {
            StatusScreen(renaming(), cutoff = fixedCutoff())
        }
        // The field opens carrying the current name, ready to be corrected rather than retyped.
        onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Anna's Birthday")),
        )
    }

    @Test
    fun `Save is inert while the name is unchanged and enables once it differs`() = runComposeUiTest {
        setContent {
            StatusScreen(renaming(), cutoff = fixedCutoff())
        }
        // A no-op rename must be unreachable, not merely rejected on a round trip.
        onNodeWithText("Save").assertIsNotEnabled()
        onNode(hasSetTextAction()).performTextInput("!")
        onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun `confirming submits the trimmed name with the membership's event id`() = runComposeUiTest {
        val submitted = mutableListOf<Pair<String, String>>()
        setContent {
            StatusScreen(
                renaming(),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    joined = JoinedActions(
                        onRenameEvent = { id, name -> submitted += id to name },
                    ),
                )
            )
        }
                onNode(hasSetTextAction()).performTextClearance()
        onNode(hasSetTextAction()).performTextInput("  Ana's 30th  ")
        onNodeWithText("Save").performClick()
        // The id rides along so a switch landing mid-edit makes the use-case a no-op.
        assertEquals(listOf("E1" to "Ana's 30th"), submitted)
    }

    @Test
    fun `a failure keeps the dialog open with the typed value and an error BANNER`() = runComposeUiTest {
        setContent {
            StatusScreen(
                renaming(RenameState.Failed("That name wasn't accepted. Try a shorter one.")),
                cutoff = fixedCutoff(),
            )
        }
                // The sheet stays open — the failure is reported beside the field, never ON it: a server saying
        // no must not read as a complaint about the host's typing.
        onNodeWithText("Save").assertExists()
        onNodeWithText("That name wasn't accepted. Try a shorter one.").assertExists()
    }

    @Test
    fun `a server failure shows the generic copy — a swept event gets no special message`() = runComposeUiTest {
        // Deliberate: a 404 is ONE witness that the event is gone, and surfacing it would invite a future
        // change to act on it (capability `leave-event`).
        setContent {
            StatusScreen(
                renaming(RenameState.Failed("Couldn't rename the event. Check your connection and try again.")),
                cutoff = fixedCutoff(),
            )
        }
                onNodeWithText("Couldn't rename the event. Check your connection and try again.").assertExists()
    }

    @Test
    fun `success closes the dialog and clears the latch`() = runComposeUiTest {
        var consumed = 0
        var dismissed = 0
        val status = mutableStateOf<RenameState>(RenameState.Idle)
        setContent {
            StatusScreen(
                renaming(status.value),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    joined = JoinedActions(onRenameStatusConsumed = { consumed++ }),
                    surfaces = SurfaceActions(onRenameDismiss = { dismissed++ }),
                )
            )
        }
        onNodeWithText("Save").assertExists()

        status.value = RenameState.Succeeded
        waitForIdle()

        // The sheet's CLOSING is the reduction's — the screen asks for it and clears the latch, so a
        // second rename starts from a clean sequence rather than re-reading this one's outcome.
        assertEquals(1, dismissed, "success asks for the sheet to close")
        assertEquals(1, consumed, "the latch is cleared so a second rename starts clean")
    }

    @Test
    fun `cancelling submits nothing`() = runComposeUiTest {
        var submits = 0
        var dismissed = 0
        setContent {
            StatusScreen(
                renaming(),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    joined = JoinedActions(onRenameEvent = { _, _ -> submits++ }),
                    surfaces = SurfaceActions(onRenameDismiss = { dismissed++ }),
                )
            )
        }
        onNode(hasSetTextAction()).performTextInput("x")
        onNodeWithText("Cancel").performClick()
        assertEquals(0, submits)
        assertEquals(1, dismissed, "cancelling asks for the sheet to close")
    }

    // ---- the settings action + reconfigure surface (capability `reconfigure-membership`) ----

    @Test
    fun `joined with a membership shows the settings action next to share and leave`() = runComposeUiTest {
        setContent { StatusScreen(inSync, cutoff = fixedCutoff()) }
        onNodeWithContentDescription("Event settings").assertExists()
        onNodeWithContentDescription("Share invite link").assertExists()
        onNodeWithContentDescription("Leave event").assertExists()
    }

    @Test
    fun `the settings action is present under needs-access — no photo access required`() = runComposeUiTest {
        setContent {
            StatusScreen(
                joined(SyncHealth.NeedsAccess(PermissionStatus.DENIED)),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithContentDescription("Event settings").assertExists()
    }

    @Test
    fun `the settings action stays offered while a pending switch is carried`() = runComposeUiTest {
        // The mirror of the rename pen above, retired for the same reason.
        setContent {
            StatusScreen(
                joined(
                    SyncHealth.InSync,
                    PendingSwitch(
                        "22222222-2222-4222-8222-222222222222",
                        phaseAt(JoinPhase.Detailed.Step.Ready, 
                            "New Event",
                            eventStart("2026-07-06T00:00:00Z"),
                            eventEnd("2026-07-16T00:00:00Z"),
                            deletesAt("2026-08-05T00:00:00Z"),
                        ),
                    ),
                ),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithContentDescription("Event settings").assertExists()
    }

    /**
     * The exact shape reported in `SNAPSYNC-26`. A join's own commit carries a pending join for the event
     * being joined, so the reduction hands the screen a `Joined` with a `pendingSwitch` for the SAME event,
     * in the `Committing` phase, for as long as provisioning takes (3.26 s in the reported log).
     * `SwitchDialog` renders nothing for `Committing`, so the only thing that state ever changed was these
     * two controls — which is the whole of the reported symptom.
     */
    @Test
    fun `a join's own commit leaves the heading and cluster controls in place`() = runComposeUiTest {
        setContent {
            StatusScreen(
                joined(
                    SyncHealth.InSync,
                    PendingSwitch(
                        MEMBERSHIP.eventId,
                        phaseAt(JoinPhase.Detailed.Step.Committing, 
                            "Anna's Birthday",
                            eventStart("2026-07-06T00:00:00Z"),
                            eventEnd("2026-07-16T00:00:00Z"),
                            deletesAt("2026-08-05T00:00:00Z"),
                        ),
                    ),
                ),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithContentDescription("Event settings").assertExists()
        onNodeWithContentDescription("Rename event").assertExists()
        // The two neighbours that were never suppressed, asserted alongside so the row is checked whole.
        onNodeWithContentDescription("Share invite link").assertExists()
        onNodeWithContentDescription("Leave event").assertExists()
    }

    @Test
    fun `tapping settings opens the reconfigure surface`() = runComposeUiTest {
        var opened = 0
        setContent {
            StatusScreen(
                inSync,
                cutoff = fixedCutoff(),
                actions = StatusActions(surfaces = SurfaceActions(onOpenReconfigure = { opened++ })),
            )
        }
        onNodeWithText("Save").assertDoesNotExist()
        onNodeWithContentDescription("Event settings").performClick()
        assertEquals(1, opened, "the gear asks the container to open the surface")
    }

    @Test
    fun `the reconfigure surface renders its controls and its own action cluster`() = runComposeUiTest {
        setContent { StatusScreen(reconfiguring(), cutoff = fixedCutoff()) }
        onNodeWithText("Save").assertExists()
        onNodeWithText("Share my photos").assertExists()
        onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun `the reconfigure surface seeds the Event-start lower bound when the cutoff is at the floor`() = runComposeUiTest {
        // minPhotoDate == startsAt → Event-start preset; maxPhotoDate == endsAt → Event-end preset. The
        // value line states the full window as the compact adaptive range.
        setContent { StatusScreen(reconfiguring(), cutoff = fixedCutoff()) }
        onNodeWithTag("from-event-start").assertIsSelected()
        onNodeWithTag("until-event-end").assertIsSelected()
        onNodeWithText("Sharing 6 Jul 12:00 – 10 Jul 12:00").assertExists()
    }

    @Test
    fun `the reconfigure surface seeds a Custom lower bound when the cutoff is above the floor`() = runComposeUiTest {
        val above = MEMBERSHIP.copy(minPhotoDate = captureCutoff("2026-07-06T18:00:00Z"))
        val form = RangeForm(fromPreset = FromChoice.CUSTOM, fromCustom = LocalDateTime(2026, 7, 6, 18, 0))
        setContent { StatusScreen(reconfiguring(above, form), cutoff = fixedCutoff()) }
        onNodeWithTag("from-custom").assertIsSelected()
        onNodeWithText("Sharing 6 Jul 18:00 – 10 Jul 12:00").assertExists()
    }

    @Test
    fun `the reconfigure surface seeds a Custom upper bound when the ceiling is below the event end`() = runComposeUiTest {
        val below = MEMBERSHIP.copy(maxPhotoDate = captureCeiling("2026-07-09T12:00:00Z"))
        val form = RangeForm(untilPreset = UntilChoice.CUSTOM, untilCustom = LocalDateTime(2026, 7, 9, 12, 0))
        setContent { StatusScreen(reconfiguring(below, form), cutoff = fixedCutoff()) }
        onNodeWithTag("until-custom").assertIsSelected()
        onNodeWithText("Sharing 6 Jul 12:00 – 9 Jul 12:00").assertExists()
    }

    @Test
    fun `turning the album on shows the forward-only helper text`() = runComposeUiTest {
        val withAlbum = MEMBERSHIP.copy(saveToAlbum = true)
        setContent {
            StatusScreen(reconfiguring(withAlbum, RangeForm(saveToAlbum = true)), cutoff = fixedCutoff())
        }
        onNodeWithText("Only photos synced from now on are added.", substring = true).assertExists()
    }

    @Test
    fun `saving asks the container to commit carrying no values of its own`() = runComposeUiTest {
        // What a Save COMMITS is what the reduction resolved — the surface renders that resolution and
        // asks for it to be applied. That the committed values round-trip a no-edit Save is
        // `StatusContainerHostTest`'s to prove; here the question is only that Save reaches the container.
        var saved = 0
        setContent {
            StatusScreen(
                reconfiguring(),
                cutoff = fixedCutoff(),
                actions = StatusActions(joined = JoinedActions(onReconfigure = { saved++ })),
            )
        }
        onNodeWithText("Save").performClick()
        assertEquals(1, saved)
    }

    @Test
    fun `cancelling asks the container to discard touching nothing else`() = runComposeUiTest {
        var cancelled = 0
        var saved = 0
        setContent {
            StatusScreen(
                reconfiguring(),
                cutoff = fixedCutoff(),
                actions = StatusActions(
                    joined = JoinedActions(onReconfigure = { saved++ }),
                    surfaces = SurfaceActions(onCancelReconfigure = { cancelled++ }),
                ),
            )
        }
        onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
        assertEquals(0, saved, "Cancel commits nothing")
    }

    // ---- joined layer: the "Event ended" marker (capability `sync-status-screen`) ----

    @Test
    fun `an ended event marks the health line on its own line`() = runComposeUiTest {
        setContent { StatusScreen(joined(SyncHealth.InSync, ended = true), cutoff = fixedCutoff()) }
        // The marker is its OWN line above the status, not an inline prefix. Asserting the EXACT text is
        // the point: an inline `Event ended · In sync` would satisfy a substring match, and reading as one
        // sentence is exactly the failure this layout exists to prevent — the two are unrelated facts (the
        // capture window closed; the transfer is still going).
        onNodeWithText("Event ended").assertExists()
        onNodeWithText("Event ended ·", substring = true).assertDoesNotExist()
        // The status itself is untouched — same value, same slot, full width.
        onNodeWithText("In sync").assertExists()
    }

    @Test
    fun `the ended marker never merges into the status text`() = runComposeUiTest {
        // A syncing health is the case that produced the original complaint: `Event ended ·
        // Synchronization pending…` parses as a claim ABOUT the syncing and wraps mid-phrase on a phone.
        setContent { StatusScreen(
                joined(SyncHealth.Syncing(Arrow.STATIC, Arrow.HIDDEN), ended = true),
                cutoff = fixedCutoff(),
            ) }
        onNodeWithText("Event ended").assertExists()
        onNodeWithText("Event ended ·", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a non-ended event shows no Event ended marker`() = runComposeUiTest {
        setContent { StatusScreen(inSync, cutoff = fixedCutoff()) }
        onNodeWithText("Event ended", substring = true).assertDoesNotExist()
        onNodeWithText("In sync").assertExists()
    }

    private fun hasAnyProgressIndication(): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
}


private fun samePixels(a: PixelMap, b: PixelMap): Boolean {
    if (a.width != b.width || a.height != b.height) return false
    for (y in 0 until a.height) for (x in 0 until a.width) if (a[x, y] != b[x, y]) return false
    return true
}

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
