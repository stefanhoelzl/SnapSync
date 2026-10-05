@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.ui

import androidx.compose.ui.semantics.SemanticsActions
import app.snapsync.model.NetworkNotice
import app.snapsync.model.AlbumKind
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertHasClickAction
import app.snapsync.model.CreateDraftSession
import app.snapsync.model.eventEnd
import app.snapsync.model.StoreKind
import app.snapsync.model.StoreLink
import app.snapsync.model.deletesAt
import app.snapsync.model.captureCutoff
import app.snapsync.model.captureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.eventStart
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.snapsync.model.GalleryAccess
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.ui.components.LocalReduceMotion
import app.snapsync.model.Arrow
import app.snapsync.model.RangeForm
import app.snapsync.model.ResolvedRange
import app.snapsync.model.JoinedSurface
import app.snapsync.model.RangeChoice
import app.snapsync.model.Overlays
import app.snapsync.model.RenameState
import app.snapsync.model.Layer
import app.snapsync.model.MemberCounts
import app.snapsync.model.DirectionCount
import app.snapsync.model.EventTiming
import app.snapsync.model.SyncCounts
import app.snapsync.model.TimeLeft
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.model.EventDetails
import app.snapsync.model.EventStart
import app.snapsync.model.EventEnd
import app.snapsync.model.DeletesAt
import app.snapsync.model.JoinPhase
import app.snapsync.model.PendingSwitch
import app.snapsync.feature.membership.readmodel.RenameFailureReason
import app.snapsync.feature.membership.readmodel.RenameStatus
import app.snapsync.model.SyncHealth
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import app.snapsync.model.UiState
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
    saveFailed: Boolean = false,
) = UiState(
    Layer.Joined(
        membership = membership,
        inviteUrl = SAMPLE_INVITE,
        health = SyncHealth.InSync,
        surface = JoinedSurface.Reconfigure(form, reconfigureResolved(membership, form), saveFailed),
    ),
)

/** What the reduction would resolve [form] to against [membership]'s own window — stated, not re-derived. */
private fun reconfigureResolved(membership: EventConfig, form: RangeForm): ResolvedRange {
    val f = fixedCutoff()
    val windowStart = f.toLocal(membership.startsAt.at)!!
    val windowEnd = f.toLocal(membership.endsAt?.at ?: membership.maxPhotoDate.at)!!
    val from = if (form.preset == RangeChoice.CUSTOM) form.customFrom ?: windowStart else windowStart
    val until = if (form.preset == RangeChoice.CUSTOM) form.customUntil ?: windowEnd else windowEnd
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
    timing: EventTiming = EventTiming.Running(TimeLeft.Days(4)),
    counts: SyncCounts? = null,
    membership: EventConfig = MEMBERSHIP,
    inviteUrl: String = SAMPLE_INVITE,
    renameState: RenameState = RenameState.Idle,
    closed: Boolean = false,
    waiting: MemberCounts? = null,
) = UiState(
    Layer.Joined(
        membership = membership,
        inviteUrl = inviteUrl,
        health = health,
        pendingSwitch = pendingSwitch,
        canChoosePhotos = canChoosePhotos,
        timing = timing,
        counts = counts,
        renameState = renameState,
        closed = closed,
        waiting = waiting,
    ),
)

private fun progress(done: Int, total: Int) = DirectionCount.Progress(done, total)
private val inSync = joined(SyncHealth.InSync)
private val syncing = joined(SyncHealth.Syncing(Arrow.PULSING, Arrow.HIDDEN))
private val syncPending = joined(SyncHealth.Syncing(Arrow.STATIC, Arrow.HIDDEN))
private val waitingForWifi = joined(SyncHealth.Syncing(Arrow.STATIC, Arrow.HIDDEN, waitingForWifi = true))

/** Longer than the create screen's clock re-read, so one advance lets an untouched start catch up. */
private const val FOLLOW_NOW_STEP = 1_100L

/** A clock the test can move: an untouched start follows it, a chosen one does not. */
private class MovableClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant
}

/** A real formatter on a fixed UTC clock — deterministic, and it actually converts. */
private fun fixedCutoff() = CutoffFormatter(
    now = { Instant.parse("2026-07-06T12:00:00Z") },
    zone = TimeZone.UTC,
)

/** The status screen on a create layer, with the wheels snapping instantly so the scene can idle. */
@Composable
private fun CreateScreen(state: UiState, cutoff: CutoffFormatter = fixedCutoff(), actions: StatusActions = testActions()) =
    CompositionLocalProvider(LocalReduceMotion provides true) { TestStatusScreen(state, cutoff = cutoff, actions = actions) }

/**
 * Set the end time to [row]:00 — the Until hour by tapping a row next to its reading line (the start's hour,
 * 12), then the minute by tapping its blank reading line (which sits over the start's :00). The hour alone
 * never sets the end.
 */
internal fun ComposeUiTest.setUntilHour(row: String) {
    // The wheels sit below the calendar, under the fold of a test window: scroll the FORM (the wheel's own
    // closest scroll parent) so the wheel is in view, then tap the row.
    onNodeWithContentDescription("End hour", useUnmergedTree = true).performScrollTo()
    onNode(hasText(row) and hasAnyAncestor(hasContentDescription("End hour")), useUnmergedTree = true).performClick()
    waitForIdle()
    onNode(hasText("--") and hasAnyAncestor(hasContentDescription("End minute")), useUnmergedTree = true).performClick()
    waitForIdle()
}

/** A complete form: [name], the last day Wednesday 8 July, and the end at 13:00. */
internal fun ComposeUiTest.completeForm(name: String) {
    onNode(hasSetTextAction()).performTextInput(name)
    onNodeWithContentDescription("Wednesday 8 July 2026").performClick()
    setUntilHour("13")
}

class StatusScreenTest {

    // ---- the not-started clock line ----

    // ---- the update-required screen (capability `app-update-required`) ----

    @Test
    fun `the update screen names the minimum and offers the store`() = runComposeUiTest {
        var opened: String? = null
        setContent {
            TestStatusScreen(
                UiState(Layer.UpdateRequired(minimumVersion = "0.4", store = StoreLink(STORE_URL, StoreKind.APP_STORE))),
                cutoff = fixedCutoff(),
                actions = testActions(onOpenLink = { opened = it }),
            )
        }
        onNodeWithText("UPDATE NEEDED").assertExists() // its own verb, not another surface's
        onNodeWithText("SnapSync 0.4 or newer is needed to keep sharing photos.").assertExists()
        onNodeWithText("Open the App Store").performClick()
        assertEquals(STORE_URL, opened)
    }

    @Test
    fun `an unstated minimum is not invented and no url means no button`() = runComposeUiTest {
        // Both absences are answers, and both must LOOK like answers. A composed store URL would be the
        // country-less form, which 404s while availability is limited to one storefront — a button that
        // lands nowhere, on the screen a member reaches because something is already wrong.
        setContent {
            TestStatusScreen(
                UiState(Layer.UpdateRequired(minimumVersion = null, store = null)),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithText("A newer version of SnapSync is needed to keep sharing photos.").assertExists()
        onNodeWithText("Open the App Store").assertDoesNotExist()
        onNodeWithText("Open Google Play").assertDoesNotExist()
    }

    @Test
    fun `on Android the update screen offers Google Play by name`() = runComposeUiTest {
        var opened: String? = null
        setContent {
            TestStatusScreen(
                UiState(Layer.UpdateRequired(minimumVersion = "0.4", store = StoreLink(PLAY_URL, StoreKind.GOOGLE_PLAY))),
                cutoff = fixedCutoff(),
                actions = testActions(onOpenLink = { opened = it }),
            )
        }
        onNodeWithText("Open the App Store").assertDoesNotExist()
        onNodeWithText("Open Google Play").performClick()
        assertEquals(PLAY_URL, opened)
    }

    @Test
    fun `before the start the dates say when and the status line says what the wait means`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                joined(SyncHealth.NotStarted, timing = EventTiming.Upcoming(TimeLeft.Days(2))),
                cutoff = fixedCutoff(),
            )
        }
        // Time is said once, on the dates line; the status line no longer restates the start.
        onNodeWithText("Mon 6 – Fri 10 Jul · starts in 2 days").assertExists()
        onNodeWithText("Sharing starts with the event").assertExists()
        onNodeWithText("Starts", substring = true).assertDoesNotExist()
        // It is information, not an action: no sync arrows, no "In sync", and no counts.
        onNodeWithText("In sync").assertDoesNotExist()
        onNodeWithText("Synchronization pending …").assertDoesNotExist()
        onNodeWithText("shared", substring = true).assertDoesNotExist()
    }

    // ---- create layer ----
    //
    // Every create test composes with reduce motion: the range picker's wheels would otherwise animate, and
    // an animating scene never idles. The fixed clock reads Monday 6 July 2026, 12:00 UTC.

    @Test
    fun `create screen shows the name input and the scan-to-join hint`() = runComposeUiTest {
        setContent { CreateScreen(UiState(Layer.CreateEvent())) }

        onNodeWithText("Create an event").assertExists()
        onNodeWithText("To join an event instead, scan its QR code with your camera.").assertExists()
        onNodeWithText("e.g. Anna's birthday").assertExists()
        onNodeWithText("Create event").assertExists()
    }

    @Test
    fun `invalid deeplink error shows below Create in place of the scan hint`() = runComposeUiTest {
        setContent { CreateScreen(UiState(Layer.CreateEvent(error = "That QR code isn't a SnapSync event."))) }
        onNodeWithText("That QR code isn't a SnapSync event.").assertExists()
        onNodeWithText("To join an event instead, scan its QR code with your camera.").assertDoesNotExist()
    }

    @Test
    fun `a create failure shows below Create in place of the scan hint`() = runComposeUiTest {
        setContent { CreateScreen(UiState(Layer.CreateEvent(error = "Couldn't connect. Check your connection and try again."))) }
        onNodeWithText("Couldn't connect. Check your connection and try again.").assertExists()
        onNodeWithText("To join an event instead, scan its QR code with your camera.").assertDoesNotExist()
    }

    @Test
    fun `the start is preset to now and the last day to today with its time blank`() = runComposeUiTest {
        setContent { CreateScreen(UiState(Layer.CreateEvent())) }
        onNodeWithText("6 Jul 2026, 12:00").assertExists()
        onNodeWithText("6 Jul 2026, pick a time").assertExists()
        onNodeWithContentDescription("End hour", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "not set"))
    }

    @Test
    fun `the line above Create names the next missing step`() = runComposeUiTest {
        setContent { CreateScreen(UiState(Layer.CreateEvent())) }
        onNodeWithText("Name the event").assertExists()
        onNodeWithText("Create event").assertIsNotEnabled()

        onNode(hasSetTextAction()).performTextInput("My Party")
        onNodeWithText("Pick an end time").assertExists()
        onNodeWithText("Create event").assertIsNotEnabled()

        // Tapping the last day alone does not complete the range.
        onNodeWithContentDescription("Wednesday 8 July 2026").performClick()
        onNodeWithText("Pick an end time").assertExists()
        onNodeWithText("Create event").assertIsNotEnabled()

        setUntilHour("13")
        onNodeWithText("Event lasts 2 days").assertExists()
        onNodeWithText("Create event").assertIsEnabled()
    }

    @Test
    fun `tapping create submits the typed name AND the chosen range`() = runComposeUiTest {
        var created: Triple<String, LocalDateTime, LocalDateTime>? = null
        setContent {
            CreateScreen(UiState(Layer.CreateEvent()), actions = testActions(onCreateEvent = { n, f, u -> created = Triple(n, f, u) }))
        }
        completeForm("My Party")
        onNodeWithText("Create event").performClick()

        // LOCAL wall-clock values; the container converts each, the screen never touches a cutoff string.
        assertEquals(Triple("My Party", LocalDateTime(2026, 7, 6, 12, 0), LocalDateTime(2026, 7, 8, 13, 0)), created)
    }

    @Test
    fun `an untouched start follows the clock while the host types`() = runComposeUiTest {
        // Capability `create-event`, "The start follows the clock until the host chooses the range": typing the
        // name is not a choice in the range, and the start shown is the start sent.
        val clock = MovableClock(Instant.parse("2026-07-06T12:00:00Z"))
        var createdFrom: LocalDateTime? = null
        setContent {
            CreateScreen(
                UiState(Layer.CreateEvent()),
                cutoff = CutoffFormatter(now = clock::now, zone = TimeZone.UTC),
                actions = testActions(onCreateEvent = { _, f, _ -> createdFrom = f }),
            )
        }
        onNode(hasSetTextAction()).performTextInput("My Party")
        clock.instant = Instant.parse("2026-07-06T12:10:00Z")
        mainClock.advanceTimeBy(FOLLOW_NOW_STEP)
        onNodeWithText("6 Jul 2026, 12:10").assertExists()

        setUntilHour("13")
        onNodeWithText("Create event").performClick()
        assertEquals(LocalDateTime(2026, 7, 6, 12, 10), createdFrom)
    }

    @Test
    fun `a choice in the range freezes the start and it is not re-derived at submit`() = runComposeUiTest {
        // The summary is the screen's statement about what will be sent. A start that silently drifted
        // after the host chose their range would make the screen lie.
        val clock = MovableClock(Instant.parse("2026-07-06T12:00:00Z"))
        var createdFrom: LocalDateTime? = null
        setContent {
            CreateScreen(
                UiState(Layer.CreateEvent()),
                cutoff = CutoffFormatter(now = clock::now, zone = TimeZone.UTC),
                actions = testActions(onCreateEvent = { _, f, _ -> createdFrom = f }),
            )
        }
        completeForm("My Party")
        clock.instant = Instant.parse("2026-07-06T12:10:00Z")
        mainClock.advanceTimeBy(FOLLOW_NOW_STEP)
        onNodeWithText("Create event").performClick()

        onNodeWithText("6 Jul 2026, 12:00").assertExists()
        assertEquals(LocalDateTime(2026, 7, 6, 12, 0), createdFrom)
    }

    @Test
    fun `a return to the foreground moves an untouched start to now and keeps the name`() = runComposeUiTest {
        val clock = MovableClock(Instant.parse("2026-07-06T12:00:00Z"))
        val state = mutableStateOf(UiState(Layer.CreateEvent(draft = CreateDraftSession(activation = 1))))
        setContent { CreateScreen(state.value, cutoff = CutoffFormatter(now = clock::now, zone = TimeZone.UTC)) }
        onNode(hasSetTextAction()).performTextInput("My Party")

        clock.instant = Instant.parse("2026-07-06T12:07:00Z")
        state.value = UiState(Layer.CreateEvent(draft = CreateDraftSession(activation = 2)))
        waitForIdle()

        onNodeWithText("6 Jul 2026, 12:07").assertExists()
        assertEquals("My Party", onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun `a short absence keeps a chosen range and a long one starts a fresh draft`() = runComposeUiTest {
        // Capability `create-event`, "A long absence starts a fresh draft": the session's epoch moves only
        // after 15 minutes away (the presentation decides that); the screen starts over on a new epoch.
        val clock = MovableClock(Instant.parse("2026-07-06T12:00:00Z"))
        val state = mutableStateOf(UiState(Layer.CreateEvent(draft = CreateDraftSession(activation = 1))))
        setContent { CreateScreen(state.value, cutoff = CutoffFormatter(now = clock::now, zone = TimeZone.UTC)) }
        completeForm("My Party")

        clock.instant = Instant.parse("2026-07-06T12:10:00Z")
        state.value = UiState(Layer.CreateEvent(draft = CreateDraftSession(activation = 2)))
        waitForIdle()
        onNodeWithText("6 Jul 2026, 12:00").assertExists()
        onNodeWithText("8 Jul 2026, 13:00").assertExists()

        clock.instant = Instant.parse("2026-07-06T12:40:00Z")
        state.value = UiState(Layer.CreateEvent(draft = CreateDraftSession(activation = 3, epoch = 1)))
        waitForIdle()
        assertEquals("", onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        onNodeWithText("6 Jul 2026, 12:40").assertExists()
        onNodeWithText("6 Jul 2026, pick a time").assertExists()
    }

    @Test
    fun `tapping the missing name puts the cursor in the name field`() = runComposeUiTest {
        setContent { CreateScreen(UiState(Layer.CreateEvent())) }
        onNodeWithText("Name the event").assertHasClickAction().performClick()
        waitForIdle()
        onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun `tapping the missing end time brings the end wheels into view without setting a time`() = runComposeUiTest {
        // A phone-sized viewport, so the wheels start below the fold as they do on a Samsung A-series.
        setContent { Box(Modifier.size(390.dp, 640.dp)) { CreateScreen(UiState(Layer.CreateEvent())) } }
        onNode(hasSetTextAction()).performTextInput("My Party")
        onNodeWithContentDescription("End hour", useUnmergedTree = true).assertIsNotDisplayed()

        onNodeWithText("Pick an end time").assertHasClickAction().performClick()
        waitForIdle()
        onNodeWithContentDescription("End hour", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("6 Jul 2026, pick a time").assertExists()
        onNodeWithText("Create event").assertIsNotEnabled()
    }

    @Test
    fun `once the end time is set the line above Create is not a button`() = runComposeUiTest {
        setContent { CreateScreen(UiState(Layer.CreateEvent())) }
        completeForm("My Party")
        onNodeWithText("Event lasts 2 days").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun `the preset start is cut to the minute it shows`() = runComposeUiTest {
        // Seconds the host cannot see must not make the created start differ from the shown one.
        var createdFrom: LocalDateTime? = null
        setContent {
            CreateScreen(
                UiState(Layer.CreateEvent()),
                cutoff = CutoffFormatter(now = { Instant.parse("2026-07-06T12:00:37Z") }, zone = TimeZone.UTC),
                actions = testActions(onCreateEvent = { _, f, _ -> createdFrom = f }),
            )
        }
        completeForm("My Party")
        onNodeWithText("Create event").performClick()
        assertEquals(LocalDateTime(2026, 7, 6, 12, 0), createdFrom)
    }

    @Test
    fun `all four time wheels are on the screen with the calendar`() = runComposeUiTest {
        setContent { CreateScreen(UiState(Layer.CreateEvent())) }
        onNodeWithText("July 2026").assertExists()
        onNodeWithContentDescription("Start hour", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "12"))
        onNodeWithContentDescription("Start minute", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "00"))
        onNodeWithContentDescription("End hour", useUnmergedTree = true).assertExists()
        onNodeWithContentDescription("End minute", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the name field caps at 100 characters`() = runComposeUiTest {
        setContent { CreateScreen(UiState(Layer.CreateEvent())) }

        val field = onNode(hasSetTextAction())
        field.performTextInput("a".repeat(100))
        field.performTextInput("b")
        val text = field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals(100, text.length)
    }

    @Test
    fun `create layer shows no sync line and no leave and no invite`() = runComposeUiTest {
        setContent { CreateScreen(UiState(Layer.CreateEvent())) }

        onNodeWithText("In sync").assertDoesNotExist()
        onNodeWithText("Synchronization", substring = true).assertDoesNotExist()
        onNodeWithContentDescription("Leave event").assertDoesNotExist()
        onNodeWithText("Others join by scanning this with their camera").assertDoesNotExist()
    }

    @Test
    fun `a failed create brings back the name and range the host entered and Create retries with them`() = runComposeUiTest {
        // Capability `create-event`, "A failed create keeps what the host entered". The form is replaced by
        // the in-flight screen while the create runs; its draft must outlive that swap. The clock moves
        // during the create, so a draft rebuilt from scratch would show a DIFFERENT start and no end.
        val clock = MovableClock(Instant.parse("2026-07-06T12:00:00Z"))
        val state = mutableStateOf(UiState(Layer.CreateEvent()))
        val submitted = mutableListOf<Triple<String, LocalDateTime, LocalDateTime>>()
        setContent {
            CreateScreen(
                state.value,
                cutoff = CutoffFormatter(now = clock::now, zone = TimeZone.UTC),
                actions = testActions(onCreateEvent = { n, f, u -> submitted += Triple(n, f, u) }),
            )
        }
        completeForm("My Party")
        onNodeWithText("Create event").performClick()

        state.value = UiState(Layer.CreatingEvent)
        waitForIdle()
        clock.instant = Instant.parse("2026-07-09T08:30:00Z")
        state.value = UiState(Layer.CreateEvent(error = "Couldn't connect. Check your connection and try again."))
        waitForIdle()

        onNodeWithText("Couldn't connect. Check your connection and try again.").assertExists()
        assertEquals("My Party", onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        onNodeWithText("6 Jul 2026, 12:00").assertExists()
        onNodeWithText("8 Jul 2026, 13:00").assertExists()
        onNodeWithText("Create event").assertIsEnabled().performClick()
        assertEquals(2, submitted.size)
        assertEquals(submitted[0], submitted[1], "the retry submits exactly what the failed attempt did")
    }

    @Test
    fun `leaving the create flow drops the draft so a later visit starts afresh`() = runComposeUiTest {
        // The draft belongs to ONE visit: a host who created, joined and later left meets an empty name, a
        // newly frozen start and a blank end time.
        val clock = MovableClock(Instant.parse("2026-07-06T12:00:00Z"))
        val state = mutableStateOf(UiState(Layer.CreateEvent()))
        setContent { CreateScreen(state.value, cutoff = CutoffFormatter(now = clock::now, zone = TimeZone.UTC)) }
        completeForm("My Party")

        state.value = inSync
        waitForIdle()
        clock.instant = Instant.parse("2026-07-09T08:30:00Z")
        state.value = UiState(Layer.CreateEvent())
        waitForIdle()

        assertEquals("", onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        onNodeWithText("9 Jul 2026, 08:30").assertExists()
        onNodeWithText("9 Jul 2026, pick a time").assertExists()
    }

    @Test
    fun `the create screen states the longest range an event can have`() = runComposeUiTest {
        setContent { CreateScreen(UiState(Layer.CreateEvent())) }
        onNodeWithText("An event can last up to 30 days", substring = true).assertExists()
    }

    @Test
    fun `a same-day event needs only the end time`() = runComposeUiTest {
        var created: Triple<String, LocalDateTime, LocalDateTime>? = null
        setContent {
            CreateScreen(UiState(Layer.CreateEvent()), actions = testActions(onCreateEvent = { n, f, u -> created = Triple(n, f, u) }))
        }
        onNode(hasSetTextAction()).performTextInput("Dinner")
        setUntilHour("13")
        onNodeWithText("Event lasts 1 hour").assertExists()
        onNodeWithText("Create event").performClick()
        assertEquals(Triple("Dinner", LocalDateTime(2026, 7, 6, 12, 0), LocalDateTime(2026, 7, 6, 13, 0)), created)
    }

    @Test
    fun `the last day cannot be picked past 30 days from the start`() = runComposeUiTest {
        // Capability `create-event`, "A range longer than 30 days cannot be chosen".
        setContent { CreateScreen(UiState(Layer.CreateEvent())) }
        onNodeWithContentDescription("Next month").performClick()
        onNodeWithContentDescription("Wednesday 5 August 2026").assertIsEnabled()  // 30 days on
        onNodeWithContentDescription("Thursday 6 August 2026").assertIsNotEnabled() // 31 days on
    }

    @Test
    fun `creating event shows a preparing indicator and no input`() = runComposeUiTest {
        setContent { TestStatusScreen(UiState(Layer.CreatingEvent), cutoff = fixedCutoff()) }

        onNodeWithText("Creating your event …").assertExists()
        onNode(hasAnyProgressIndication()).assertExists()
        onNodeWithText("e.g. Anna's birthday").assertDoesNotExist()
    }

    // ---- joined layer: status line ----

    @Test
    fun `in sync shows the settled line and no counts`() = runComposeUiTest {
        setContent { TestStatusScreen(inSync, cutoff = fixedCutoff()) }

        onNodeWithText("In sync").assertExists()
        onNodeWithText("images synced", substring = true).assertDoesNotExist()
    }

    @Test
    fun `syncing with an in-flight arrow reads ongoing`() = runComposeUiTest {
        setContent { TestStatusScreen(syncing, cutoff = fixedCutoff()) }

        onNodeWithText("Synchronization ongoing…").assertExists()
        onNodeWithText("images synced", substring = true).assertDoesNotExist()
    }

    @Test
    fun `syncing with a static arrow reads pending`() = runComposeUiTest {
        setContent { TestStatusScreen(syncPending, cutoff = fixedCutoff()) }

        onNodeWithText("Synchronization pending…").assertExists()
        onNodeWithText("Synchronization ongoing…").assertDoesNotExist()
    }

    /** Capability `mobile-data`: photos kept off mobile data on a network that choice avoids say why they wait. */
    @Test
    fun `photos held for Wi-Fi read waiting for Wi-Fi`() = runComposeUiTest {
        setContent { TestStatusScreen(waitingForWifi, cutoff = fixedCutoff()) }

        onNodeWithText("Waiting for Wi-Fi…").assertExists()
        onNodeWithText("Synchronization pending…").assertDoesNotExist()
    }

    // ---- reduce motion (`docs/architecture.md`) ----

    /**
     * The requirement is an **absence** — "SHALL respect reduced-motion preferences" — so the test asserts
     * one: render two frames a third of a pulse apart and prove the pixels are identical. That is the
     * property itself, not a proxy for it. The control below is what makes it mean anything.
     */
    @Test
    fun `reduce motion leaves the pulsing arrow un-animated`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) { TestStatusScreen(syncing, cutoff = fixedCutoff()) }
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
            CompositionLocalProvider(LocalReduceMotion provides false) { TestStatusScreen(syncing, cutoff = fixedCutoff()) }
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
            CompositionLocalProvider(LocalReduceMotion provides true) { TestStatusScreen(syncPending, cutoff = fixedCutoff()) }
        }

        onNodeWithText("Synchronization pending…").assertExists()
        onNodeWithText("Synchronization ongoing…").assertDoesNotExist()
    }

    @Test
    fun `needs-access not-determined shows the allow copy and taps request permission`() = runComposeUiTest {
        var requests = 0
        setContent {
            TestStatusScreen(
                joined(SyncHealth.NeedsAccess(GalleryAccess.NOT_DETERMINED)),
                actions = testActions(
                    access = testAccessActions(
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
            TestStatusScreen(
                joined(SyncHealth.NeedsAccess(GalleryAccess.DENIED)),
                actions = testActions(
                    access = testAccessActions(
                        onOpenSettings = { settingsOpens++ },
                    ),
                ),
             cutoff = fixedCutoff())
        }

        onNodeWithText("Allow photo access in Settings").assertExists()
        onNodeWithText("Allow photo access in Settings").performClick()
        assertEquals(1, settingsOpens)
    }

    // ---- no network (capabilities `sync-status`, `create-event`) ----

    @Test
    fun `a blocked network's status line opens Settings`() = runComposeUiTest {
        var settingsOpens = 0
        setContent {
            TestStatusScreen(
                joined(SyncHealth.NoNetwork(NetworkNotice.BLOCKED)),
                actions = testActions(access = testAccessActions(onOpenSettings = { settingsOpens++ })),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithText("Network blocked for SnapSync – Open Settings").performClick()
        assertEquals(1, settingsOpens)
    }

    @Test
    fun `the offline status line offers nothing`() = runComposeUiTest {
        var settingsOpens = 0
        setContent {
            TestStatusScreen(
                joined(SyncHealth.NoNetwork(NetworkNotice.OFFLINE)),
                actions = testActions(access = testAccessActions(onOpenSettings = { settingsOpens++ })),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithText("You're offline").assertExists().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        onNodeWithText("You're offline").performClick()
        assertEquals(0, settingsOpens)
    }

    @Test
    fun `offline Create cannot be tapped and the line below it says so`() = runComposeUiTest {
        setContent { CreateScreen(UiState(Layer.CreateEvent(error = "Couldn't connect. Check your connection and try again.", network = NetworkNotice.OFFLINE))) }
        completeForm("Party")
        onNodeWithText("You're offline").assertExists()
        onNodeWithText("Couldn't connect. Check your connection and try again.").assertDoesNotExist()
        onNodeWithText("Create event").assertIsNotEnabled()
    }

    @Test
    fun `blocked the create screen offers SnapSync’s Settings`() = runComposeUiTest {
        var settingsOpens = 0
        setContent {
            CreateScreen(
                UiState(Layer.CreateEvent(network = NetworkNotice.BLOCKED)),
                actions = testActions(access = testAccessActions(onOpenSettings = { settingsOpens++ })),
            )
        }
        onNodeWithText("Network blocked for SnapSync – Open Settings").performClick()
        assertEquals(1, settingsOpens)
    }

    // ---- joined layer: partial-grant resting affordances (capability `photo-access`) ----

    @Test
    fun `limited grant shows both affordances in order — in every health`() = runComposeUiTest {
        // One recomposing scene walks the healths — the affordances are resting offers, present
        // regardless of the current health value, with the grant switch always BELOW the selection
        // widening (the cheaper step leads).
        val state = mutableStateOf<UiState>(joined(SyncHealth.InSync, canChoosePhotos = true))
        setContent { TestStatusScreen(state.value, cutoff = fixedCutoff()) }

        val healths = listOf(
            SyncHealth.InSync,
            SyncHealth.Syncing(Arrow.PULSING, Arrow.HIDDEN),
            SyncHealth.NotStarted,
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
            TestStatusScreen(
                joined(SyncHealth.InSync, canChoosePhotos = true),
                cutoff = fixedCutoff(),
                actions = testActions(
                    access = testAccessActions(
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
            TestStatusScreen(
                joined(SyncHealth.InSync, canChoosePhotos = true),
                cutoff = fixedCutoff(),
                actions = testActions(
                    access = testAccessActions(
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
        setContent { TestStatusScreen(inSync, cutoff = fixedCutoff()) }

        onNodeWithText("Choose more photos").assertDoesNotExist()
        onNodeWithText("Allow full access").assertDoesNotExist()
    }

    // ---- joined layer: name, leave, invite ----

    @Test
    fun `joined shows the event name as the title`() = runComposeUiTest {
        setContent { TestStatusScreen(inSync, cutoff = fixedCutoff()) }
        onNodeWithText("Anna's Birthday").assertExists()
    }

    @Test
    fun `joined shows the leave action`() = runComposeUiTest {
        setContent { TestStatusScreen(inSync, cutoff = fixedCutoff()) }
        onNodeWithContentDescription("Leave event").assertExists()
    }

    @Test
    fun `needs-access still shows leave and invite — sharing needs no access`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                joined(SyncHealth.NeedsAccess(GalleryAccess.DENIED)),
             cutoff = fixedCutoff())
        }
        onNodeWithContentDescription("Leave event").assertExists()
        onNodeWithText("Others join by scanning this with their camera").assertExists()
        onNodeWithContentDescription("Share invite link").assertExists()
    }

    @Test
    fun `activating leave asks for the confirmation`() = runComposeUiTest {
        var asked = 0
        setContent {
            TestStatusScreen(
                inSync,
                cutoff = fixedCutoff(),
                actions = testActions(surfaces = testSurfaceActions(onConfirmLeaveOpen = { asked++ })),
            )
        }
        onNodeWithText("Leave this event?").assertDoesNotExist()
        onNodeWithContentDescription("Leave event").performClick()
        assertEquals(1, asked)
    }

    @Test
    fun `the leave confirmation renders when the state says it is up`() = runComposeUiTest {
        setContent { TestStatusScreen(confirmingLeave(), cutoff = fixedCutoff()) }
        onNodeWithText("Leave this event?").assertExists()
    }

    @Test
    fun `confirming leave invokes the callback`() = runComposeUiTest {
        var leaves = 0
        setContent {
            TestStatusScreen(
                confirmingLeave(),
                cutoff = fixedCutoff(),
                actions = testActions(joined = testJoinedActions(onLeaveEvent = { leaves++ })),
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
            TestStatusScreen(
                confirmingLeave(),
                cutoff = fixedCutoff(),
                actions = testActions(
                    joined = testJoinedActions(onLeaveEvent = { leaves++ }),
                    surfaces = testSurfaceActions(onConfirmLeaveDismiss = { dismissed++ }),
                ),
            )
        }
        onNodeWithText("Stay").performClick()
        assertEquals(0, leaves)
        assertEquals(1, dismissed)
    }

    @Test
    fun `joined shows the invite QR and share action`() = runComposeUiTest {
        setContent { TestStatusScreen(inSync, cutoff = fixedCutoff()) }
        onNodeWithText("Others join by scanning this with their camera").assertExists()
        onNodeWithContentDescription("Share invite link").assertExists()
    }

    @Test
    fun `activating share invokes the callback`() = runComposeUiTest {
        var shares = 0
        setContent { TestStatusScreen(inSync, cutoff = fixedCutoff(), actions = testActions(joined = testJoinedActions(onShareInvite = { shares++ }))) }
        onNodeWithContentDescription("Share invite link").performClick()
        assertEquals(1, shares)
    }

    // ---- the rename affordance + dialog (capability `manage-membership`) ----

    @Test
    fun `joined with a membership shows the rename pen beside the heading`() = runComposeUiTest {
        setContent {
            TestStatusScreen(inSync, cutoff = fixedCutoff())
        }
        onNodeWithText("Anna's Birthday").assertExists()
        onNodeWithContentDescription("Rename event").assertExists()
    }

    @Test
    fun `the rename pen is present in every joined health — including without photo access`() = runComposeUiTest {
        // Renaming needs neither photo access nor a started event, so no health value may hide it.
        val health = mutableStateOf<SyncHealth>(SyncHealth.InSync)
        setContent {
            TestStatusScreen(
                joined(health.value),
                cutoff = fixedCutoff(),
            )
        }
        for (value in listOf(
            SyncHealth.InSync,
            SyncHealth.Syncing(Arrow.PULSING, Arrow.HIDDEN),
            SyncHealth.NeedsAccess(GalleryAccess.DENIED),
            SyncHealth.NeedsAccess(GalleryAccess.NOT_DETERMINED),
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
            TestStatusScreen(
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
        setContent { TestStatusScreen(UiState(Layer.CreateEvent()), cutoff = fixedCutoff()) }
        onNodeWithContentDescription("Rename event").assertDoesNotExist()
    }

    @Test
    fun `the rename pen is absent while the reconfigure surface is open`() = runComposeUiTest {
        setContent { TestStatusScreen(reconfiguring(), cutoff = fixedCutoff()) }
        onNodeWithContentDescription("Rename event").assertDoesNotExist()
    }

    @Test
    fun `the pen asks for the rename dialog`() = runComposeUiTest {
        var asked = 0
        setContent {
            TestStatusScreen(
                inSync,
                cutoff = fixedCutoff(),
                actions = testActions(surfaces = testSurfaceActions(onRenameOpen = { asked++ })),
            )
        }
        onNodeWithContentDescription("Rename event").performClick()
        assertEquals(1, asked)
    }

    @Test
    fun `the rename dialog opens PRE-FILLED with the current name`() = runComposeUiTest {
        setContent {
            TestStatusScreen(renaming(), cutoff = fixedCutoff())
        }
        // The field opens carrying the current name, ready to be corrected rather than retyped.
        onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Anna's Birthday")),
        )
    }

    @Test
    fun `Save is inert while the name is unchanged and enables once it differs`() = runComposeUiTest {
        setContent {
            TestStatusScreen(renaming(), cutoff = fixedCutoff())
        }
        // A no-op rename must be unreachable, not merely rejected on a round trip.
        onNodeWithText("Save").assertIsNotEnabled()
        onNode(hasSetTextAction()).performTextInput("!")
        onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun `confirming submits the trimmed name with the membership’s event id`() = runComposeUiTest {
        val submitted = mutableListOf<Pair<String, String>>()
        setContent {
            TestStatusScreen(
                renaming(),
                cutoff = fixedCutoff(),
                actions = testActions(
                    joined = testJoinedActions(
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
            TestStatusScreen(
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
        // change to act on it (capability `manage-membership`).
        setContent {
            TestStatusScreen(
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
            TestStatusScreen(
                renaming(status.value),
                cutoff = fixedCutoff(),
                actions = testActions(
                    joined = testJoinedActions(onRenameStatusConsumed = { consumed++ }),
                    surfaces = testSurfaceActions(onRenameDismiss = { dismissed++ }),
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
            TestStatusScreen(
                renaming(),
                cutoff = fixedCutoff(),
                actions = testActions(
                    joined = testJoinedActions(onRenameEvent = { _, _ -> submits++ }),
                    surfaces = testSurfaceActions(onRenameDismiss = { dismissed++ }),
                )
            )
        }
        onNode(hasSetTextAction()).performTextInput("x")
        onNodeWithText("Cancel").performClick()
        assertEquals(0, submits)
        assertEquals(1, dismissed, "cancelling asks for the sheet to close")
    }

    // ---- the settings action + reconfigure surface (capability `manage-membership`) ----

    @Test
    fun `joined with a membership shows the settings action next to share and leave`() = runComposeUiTest {
        setContent { TestStatusScreen(inSync, cutoff = fixedCutoff()) }
        onNodeWithContentDescription("Event settings").assertExists()
        onNodeWithContentDescription("Share invite link").assertExists()
        onNodeWithContentDescription("Leave event").assertExists()
    }

    @Test
    fun `the settings action is present under needs-access — no photo access required`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                joined(SyncHealth.NeedsAccess(GalleryAccess.DENIED)),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithContentDescription("Event settings").assertExists()
    }

    @Test
    fun `the settings action stays offered while a pending switch is carried`() = runComposeUiTest {
        // The mirror of the rename pen above, retired for the same reason.
        setContent {
            TestStatusScreen(
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
    fun `a join’s own commit leaves the heading and cluster controls in place`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
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
            TestStatusScreen(
                inSync,
                cutoff = fixedCutoff(),
                actions = testActions(surfaces = testSurfaceActions(onOpenReconfigure = { opened++ })),
            )
        }
        onNodeWithText("Save").assertDoesNotExist()
        onNodeWithContentDescription("Event settings").performClick()
        assertEquals(1, opened, "the gear asks the container to open the surface")
    }

    @Test
    fun `the reconfigure surface renders its controls and its own action cluster`() = runComposeUiTest {
        setContent { TestStatusScreen(reconfiguring(), cutoff = fixedCutoff()) }
        onNodeWithText("Save").assertExists()
        onNodeWithText("Share my photos").assertExists()
        onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun `a failed save keeps the surface and says nothing changed`() = runComposeUiTest {
        setContent { TestStatusScreen(reconfiguring(saveFailed = true), cutoff = fixedCutoff()) }
        onNodeWithText("Your settings couldn't be saved, so nothing changed. Try again.").assertExists()
        onNodeWithText("Save").assertExists()
    }

    @Test
    fun `an open surface with no failed save shows no failure`() = runComposeUiTest {
        setContent { TestStatusScreen(reconfiguring(), cutoff = fixedCutoff()) }
        onNodeWithText("couldn't be saved", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the reconfigure surface shows the whole event when the range spans the window`() = runComposeUiTest {
        // minPhotoDate == startsAt and maxPhotoDate == endsAt → the whole event. The row states the full window
        // as the compact adaptive range.
        setContent { TestStatusScreen(reconfiguring(), cutoff = fixedCutoff()) }
        onNodeWithText("6 Jul 12:00 – 10 Jul 12:00").assertExists()
        onNodeWithText("The whole event", substring = true).assertExists()
    }

    @Test
    fun `the reconfigure surface shows a custom range when the cutoff is above the floor`() = runComposeUiTest {
        val above = MEMBERSHIP.copy(minPhotoDate = captureCutoff("2026-07-06T18:00:00Z"))
        val form = RangeForm(preset = RangeChoice.CUSTOM, customFrom = LocalDateTime(2026, 7, 6, 18, 0))
        setContent { TestStatusScreen(reconfiguring(above, form), cutoff = fixedCutoff()) }
        onNodeWithText("6 Jul 18:00 – 10 Jul 12:00").assertExists()
        onNodeWithText("Custom range", substring = true).assertExists()
    }

    @Test
    fun `the reconfigure surface shows a custom range when the ceiling is below the event end`() = runComposeUiTest {
        val below = MEMBERSHIP.copy(maxPhotoDate = captureCeiling("2026-07-09T12:00:00Z"))
        val form = RangeForm(preset = RangeChoice.CUSTOM, customUntil = LocalDateTime(2026, 7, 9, 12, 0))
        setContent { TestStatusScreen(reconfiguring(below, form), cutoff = fixedCutoff()) }
        onNodeWithText("6 Jul 12:00 – 9 Jul 12:00").assertExists()
        onNodeWithText("Custom range", substring = true).assertExists()
    }

    @Test
    fun `turning the album on says the already-synced photos are included`() = runComposeUiTest {
        val withAlbum = MEMBERSHIP.copy(saveToAlbum = true)
        setContent {
            TestStatusScreen(reconfiguring(withAlbum, RangeForm(saveToAlbum = true)), cutoff = fixedCutoff())
        }
        onNodeWithText("including the ones you already have", substring = true).assertExists()
        onNodeWithText("from now on", substring = true).assertDoesNotExist()
    }

    @Test
    fun `turning a folder album on says the already-received photos are included and own photos stay`() = runComposeUiTest {
        // Capability `manage-membership`: on Android the album gathers what was received, never the member's own photos.
        val withAlbum = MEMBERSHIP.copy(saveToAlbum = true)
        setContent {
            TestStatusScreen(
                reconfiguring(withAlbum, RangeForm(saveToAlbum = true, albumKind = AlbumKind.FOLDER)),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithText("including the ones already received", substring = true).assertExists()
        onNodeWithText("Your own photos stay where they are", substring = true).assertExists()
    }

    @Test
    fun `saving asks the container to commit carrying no values of its own`() = runComposeUiTest {
        // What a Save COMMITS is what the reduction resolved — the surface renders that resolution and
        // asks for it to be applied. That the committed values round-trip a no-edit Save is
        // `StatusContainerHostTest`'s to prove; here the question is only that Save reaches the container.
        var saved = 0
        setContent {
            TestStatusScreen(
                reconfiguring(),
                cutoff = fixedCutoff(),
                actions = testActions(joined = testJoinedActions(onReconfigure = { saved++ })),
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
            TestStatusScreen(
                reconfiguring(),
                cutoff = fixedCutoff(),
                actions = testActions(
                    joined = testJoinedActions(onReconfigure = { saved++ }),
                    surfaces = testSurfaceActions(onCancelReconfigure = { cancelled++ }),
                ),
            )
        }
        onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
        assertEquals(0, saved, "Cancel commits nothing")
    }

    // ---- joined layer: the heading's joined statement and dates (capability `sync-status`) ----

    @Test
    fun `the heading says the device has joined and how long the event lasts`() = runComposeUiTest {
        setContent { TestStatusScreen(inSync, cutoff = fixedCutoff()) }
        onNodeWithText("Anna's Birthday").assertExists()
        onNodeWithText("You've joined this event").assertExists()
        onNodeWithText("Mon 6 – Fri 10 Jul · ends in 4 days").assertExists()
    }

    @Test
    fun `the last day counts down in hours and then minutes`() = runComposeUiTest {
        val state = mutableStateOf(joined(SyncHealth.InSync, timing = EventTiming.Running(TimeLeft.Hours(5))))
        setContent { TestStatusScreen(state.value, cutoff = fixedCutoff()) }
        onNodeWithText("Mon 6 – Fri 10 Jul · ends in 5 hours").assertExists()
        state.value = joined(SyncHealth.InSync, timing = EventTiming.Running(TimeLeft.Minutes(40)))
        onNodeWithText("Mon 6 – Fri 10 Jul · ends in 40 min").assertExists()
        state.value = joined(SyncHealth.InSync, timing = EventTiming.Running(TimeLeft.Hours(1)))
        onNodeWithText("Mon 6 – Fri 10 Jul · ends in 1 hour").assertExists()
    }

    @Test
    fun `a same-day event today shows its times`() = runComposeUiTest {
        val party = MEMBERSHIP.copy(
            startsAt = eventStart("2026-07-06T18:00:00Z"),
            endsAt = eventEnd("2026-07-06T23:00:00Z"),
        )
        setContent {
            TestStatusScreen(
                joined(SyncHealth.NotStarted, membership = party, timing = EventTiming.Upcoming(TimeLeft.Hours(6))),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithText("Today 18:00 – 23:00 · starts in 6 hours").assertExists()
    }

    @Test
    fun `an ended event says so on the dates line and never in the status text`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                joined(SyncHealth.Syncing(Arrow.STATIC, Arrow.HIDDEN), timing = EventTiming.Ended),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithText("Mon 6 – Fri 10 Jul · ended").assertExists()
        onNodeWithText("Event ended", substring = true).assertDoesNotExist()
        onNodeWithText("Synchronization pending…").assertExists()
    }

    // ---- joined layer: the counts line (capability `sync-status`) ----

    @Test
    fun `the counts show both directions while work remains`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                joined(SyncHealth.Syncing(Arrow.PULSING, Arrow.STATIC), counts = SyncCounts(progress(12, 15), progress(40, 52))),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithText("Synchronization ongoing…").assertExists()
        onNodeWithText("12/15 shared · 40/52 received").assertExists()
    }

    @Test
    fun `in sync the counts show totals`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                joined(SyncHealth.InSync, counts = SyncCounts(progress(15, 15), progress(52, 52))),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithText("In sync").assertExists()
        onNodeWithText("15 shared · 52 received").assertExists()
    }

    @Test
    fun `a switched-off direction says so`() = runComposeUiTest {
        val state = mutableStateOf(
            joined(SyncHealth.Syncing(Arrow.HIDDEN, Arrow.STATIC), counts = SyncCounts(DirectionCount.Off, progress(40, 52))),
        )
        setContent { TestStatusScreen(state.value, cutoff = fixedCutoff()) }
        onNodeWithText("Not sharing · 40/52 received").assertExists()
        state.value = joined(SyncHealth.InSync, counts = SyncCounts(progress(15, 15), DirectionCount.Off))
        onNodeWithText("15 shared · Not receiving").assertExists()
    }

    @Test
    fun `no counts are drawn when the state carries none`() = runComposeUiTest {
        setContent { TestStatusScreen(joined(SyncHealth.NeedsAccess(GalleryAccess.DENIED)), cutoff = fixedCutoff()) }
        onNodeWithText("shared", substring = true).assertDoesNotExist()
        onNodeWithText("received", substring = true).assertDoesNotExist()
    }

    @Test
    fun `limited access shows its counts beside the two access offers`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                joined(SyncHealth.InSync, canChoosePhotos = true, counts = SyncCounts(progress(6, 6), progress(52, 52))),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithText("6 shared · 52 received").assertExists()
        onNodeWithText("Choose more photos").assertExists()
        onNodeWithText("Allow full access").assertExists()
    }

    // ---- joined layer: early completion (capabilities `sync-status`, `manage-membership`) ----

    @Test
    fun `an ended open event says who it is still waiting for beneath the counts`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                joined(
                    SyncHealth.InSync, timing = EventTiming.Ended, waiting = MemberCounts(5, 3),
                    counts = SyncCounts(progress(34, 34), progress(110, 110)),
                ),
                cutoff = fixedCutoff(),
            )
        }
        onNodeWithText("34 shared · 110 received").assertExists()
        onNodeWithText("Waiting for 2 of 5 members").assertExists()
        onNodeWithText("In sync").assertExists()
    }

    @Test
    fun `a closed event offers only Leave`() = runComposeUiTest {
        setContent {
            TestStatusScreen(joined(SyncHealth.InSync, timing = EventTiming.Ended, closed = true), cutoff = fixedCutoff())
        }
        onNodeWithContentDescription("Share invite link").assertDoesNotExist()
        onNodeWithContentDescription("Event settings").assertDoesNotExist()
        onNodeWithContentDescription("Rename event").assertDoesNotExist()
        onNodeWithText("Others join by scanning this with their camera").assertDoesNotExist()
        onNodeWithText("Invite others", ignoreCase = true).assertDoesNotExist()
        onNodeWithContentDescription("Leave event").assertExists()
        onNodeWithText("In sync").assertExists()
        // The name, the joined statement and the dates stay.
        onNodeWithText("You've joined this event").assertExists()
        onNodeWithText("Mon 6 – Fri 10 Jul · ended").assertExists()
    }

    @Test
    fun `the invite is labelled as an invitation for others`() = runComposeUiTest {
        setContent { TestStatusScreen(inSync, cutoff = fixedCutoff()) }
        onNodeWithText("Invite others", ignoreCase = true).assertExists()
        onNodeWithText("Share this event", ignoreCase = true).assertDoesNotExist()
        onNodeWithText("Others join by scanning this with their camera").assertExists()
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

/** The store link a build carries, offered on the update-required screen. */
private const val STORE_URL = "https://apps.apple.com/de/app/id6781692480"
private const val PLAY_URL = "https://play.google.com/store/apps/details?id=app.snapsync"
