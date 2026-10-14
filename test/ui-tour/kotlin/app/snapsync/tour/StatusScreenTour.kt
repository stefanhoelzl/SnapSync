@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.tour

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import app.snapsync.contracts.UiUnderTest
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.domain.version.VersionRefusal
import app.snapsync.feature.status.readmodel.NetworkStatusSource
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.GalleryAccess
import app.snapsync.model.JoinChoice
import app.snapsync.model.JoinCommit
import app.snapsync.model.JoinLoad
import app.snapsync.model.NetworkAccess
import app.snapsync.model.ReconfigureOutcome
import app.snapsync.model.ReportContext
import app.snapsync.model.ReportOutcome
import app.snapsync.model.StoreKind
import app.snapsync.model.StoreLink
import app.snapsync.model.SyncStatus
import app.snapsync.model.TimestampText
import app.snapsync.model.UserCommands
import app.snapsync.model.UserQueries
import app.snapsync.model.encodeEventUrl
import app.snapsync.ports.Ui
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.DeviceVerification
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.StatusDiagnostics
import app.snapsync.presentation.StatusSources
import app.snapsync.presentation.onIntent
import app.snapsync.ui.components.resources.menu
import app.snapsync.ui.components.resources.menu_close
import app.snapsync.ui.components.resources.share_range_custom
import app.snapsync.ui.components.resources.share_range_event_start
import app.snapsync.ui.components.resources.share_range_from_now
import app.snapsync.ui.components.resources.status_allow_access
import app.snapsync.ui.components.resources.status_allow_access_settings
import app.snapsync.ui.components.resources.wheel_end_hour
import app.snapsync.ui.components.resources.wheel_end_minute
import app.snapsync.ui.components.resources.wheel_start_hour
import app.snapsync.ui.components.resources.wheel_start_minute
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.album_toggle
import app.snapsync.ui.resources.allow_full_access
import app.snapsync.ui.resources.cancel
import app.snapsync.ui.resources.choose_more_photos
import app.snapsync.ui.resources.create_button
import app.snapsync.ui.resources.footer_settings
import app.snapsync.ui.resources.join_button
import app.snapsync.ui.resources.leave_cancel
import app.snapsync.ui.resources.leave_confirm
import app.snapsync.ui.resources.leave_event
import app.snapsync.ui.resources.menu_website
import app.snapsync.ui.resources.message_device_unverifiable
import app.snapsync.ui.resources.message_report_this
import app.snapsync.ui.resources.mobile_data_toggle
import app.snapsync.ui.resources.receive_toggle
import app.snapsync.ui.resources.rename_event
import app.snapsync.ui.resources.report_placeholder
import app.snapsync.ui.resources.report_problem
import app.snapsync.ui.resources.report_send
import app.snapsync.ui.resources.report_sent
import app.snapsync.ui.resources.retry
import app.snapsync.ui.resources.save
import app.snapsync.ui.resources.share_invite
import app.snapsync.ui.resources.share_toggle
import app.snapsync.ui.resources.show_qr
import app.snapsync.ui.resources.stop_sharing_confirm
import app.snapsync.ui.resources.stop_sharing_keep
import app.snapsync.ui.resources.store_app_store
import app.snapsync.ui.resources.switch_confirm
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.JoinedSurface
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RangeChoice
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.UiState
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.concurrent.Volatile
import kotlin.time.Instant
import app.snapsync.ui.components.resources.Res as ComponentRes

/** The event the joined parts of the tour are members of. */
private const val JOINED_ID = "11111111-1111-4111-8111-111111111111"

/** A second event, for the join gate and the switch. */
private const val OTHER_ID = "22222222-2222-4222-8222-222222222222"

private const val STORE_URL = "https://apps.apple.com/de/app/snapsync/id0000000000"

private const val WAIT_MS = 10_000L
private const val SOURCE = "StatusScreenTour.kt"

/** A tap just inside the top of the sheet's scrim: above the sheet, where a tap closes it. */
private const val ABOVE_THE_SHEET_PX = 5f

private val MEMBERSHIP = EventConfig(
    eventId = EventId.parse(JOINED_ID)!!,
    name = "Anna’s Birthday",
    minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-04T18:00:00Z")),
    startsAt = EventStart(TimestampText.parse("2026-07-04T18:00:00Z")),
    endsAt = EventEnd(TimestampText.parse("2026-07-20T18:00:00Z")),
    maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-20T18:00:00Z")),
    deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
)

private val OTHER_EVENT = JoinLoad.Found(
    "Anna’s Wedding",
    EventStart(TimestampText.parse("2026-07-04T18:00:00Z")),
    EventEnd(TimestampText.parse("2026-07-20T18:00:00Z")),
    DeletesAt(TimestampText.parse("2026-08-03T18:00:00Z")),
)

private val CUTOFF = CutoffFormatter(now = { Instant.parse("2026-07-06T12:00:00Z") }, zone = TimeZone.UTC)

private fun linkTo(eventId: String) = encodeEventUrl(EventLinkPayload(EventId.parse(eventId)!!))

// A test reads the words the screen shows; production never blocks on a resource.
private fun str(res: StringResource): String = runBlocking { getString(res) }

/**
 * The status screen's tour, shared by every platform's [Ui] adapter binding (`docs/testing.md`, "The Ui contract"): the
 * screen the adapter hands its host — pulled through the adapter's own entry, [content] — inside a Compose UI test,
 * every control tapped. What the screen shows is a real [StatusContainerHost]'s state, pushed through the adapter's
 * `show`; each intent the clause's handler receives is reduced by that container ([react]), so a tap that opens a dialog
 * really opens it. The tour swaps the container between parts — a membership, a refusal, a grant — but never the
 * adapter, so every tap reaches the one handler the clause listened with.
 *
 * Compiled into each binding's test source set (as the Sentry adapter's `src/wireTest` is), never a module: the
 * binding itself — its host, its adapter — stays in the platform's file, where the contract-coverage gate reads it.
 */
abstract class StatusScreenTour {

    /** The adapter under test, bare. */
    protected abstract val ui: Ui<UiState, UiIntent>

    /** The screen as the adapter hands it to its host: its own entry, which calls the clause's `onLive`. */
    protected abstract fun content(): @Composable () -> Unit

    /** How the platform's test hosts the screen; the screen as it is, by default. */
    protected open val host: @Composable (@Composable () -> Unit) -> Unit = { it() }

    /**
     * [ui] as the clause's call log sees it: what the tour shows each state through, since showing is the clause's own
     * act. The pull of the screen ([content]) is no port call, so it stays on [ui].
     */
    @Volatile
    protected var port: Ui<UiState, UiIntent>? = null

    /** The container the screen currently shows; [react] reduces into it. */
    @Volatile
    private var current: Rig? = null

    /** The last state [port] was shown — what the screen renders once it is idle. */
    @Volatile
    private var shown: UiState? = null

    /** The UI test hosting the screen the tour brings up. */
    @Volatile
    protected var screenTest: ComposeUiTest? = null

    /** What a clause's handler does with an intent: the current container reduces it. */
    protected fun react(intent: UiIntent) {
        current?.host?.onIntent(intent)
    }

    /** Ends the container the screen shows. */
    protected fun retire() {
        current?.scope?.cancel()
    }

    /** The tour, inside the UI test the platform's binding runs the contract in. */
    protected fun runTour() = checkNotNull(screenTest) { "the tour runs inside the platform's UI test" }.tour()

    /** The last state the screen was shown — what a screen brought up live renders first. */
    protected fun shownNow(): UiState = checkNotNull(shown) { "the screen was shown nothing yet" }

    /** A real container over plain cells, its every state shown through [ui]. */
    private inner class Rig(
        config: EventConfig? = null,
        permission: GalleryAccess = GalleryAccess.GRANTED,
        refusal: VersionRefusal? = null,
        deviceRefusal: DeviceRefusal? = null,
        private val details: suspend (EventId) -> JoinLoad = { OTHER_EVENT },
    ) {
        val config = MutableStateFlow(config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val host = StatusContainerHost(
            StatusSources(
                sync = object : SyncStatusSource {
                    override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Loading)
                },
                permission = MutableStateFlow(permission),
                config = this.config,
                versionRefusal = MutableStateFlow(refusal),
                verification = DeviceVerification(refusal = MutableStateFlow(deviceRefusal)),
                store = StoreLink(STORE_URL, StoreKind.APP_STORE),
                network = object : NetworkStatusSource {
                    override val access: StateFlow<NetworkAccess> = MutableStateFlow(
                        NetworkAccess.Online(restricted = false),
                    )
                    override val returned: Flow<Unit> = emptyFlow()
                },
            ),
            scope = scope,
            cutoffFormatter = CUTOFF,
            commands = object : UserCommands {
                override suspend fun leave() {
                    this@Rig.config.value = null
                }

                override fun create(name: String, startsAt: EventStart, endsAt: EventEnd, openUntil: OpenUntil?) = Unit

                override suspend fun commitJoin(choice: JoinChoice) = JoinCommit.Failed()

                override fun share(url: String, title: String) = Unit

                override fun requestAccess() = Unit

                override fun openSettings() = Unit

                override fun openLink(url: String) = Unit

                override fun choosePhotos() = Unit

                override suspend fun reconfigure(
                    eventId: EventId,
                    direction: Direction,
                    minPhotoDate: CaptureCutoff,
                    maxPhotoDate: CaptureCeiling,
                    saveToAlbum: Boolean,
                ) = ReconfigureOutcome.Saved

                override fun rename(eventId: EventId, name: String) = Unit

                override suspend fun resetRename() = Unit

                override suspend fun sendDiagnostics(note: String, context: ReportContext) = ReportOutcome.SENT

                override suspend fun setMobileData(on: Boolean) = true

                override suspend fun restoreEventKey(linkKey: String) = false
            },
            queries = object : UserQueries {
                override suspend fun loadJoinDetails(eventId: EventId, linkKey: String?) = details(eventId)

                override suspend fun shareableCount(cutoff: CaptureCutoff, until: CaptureCeiling?): Int? = null

                override suspend fun switchLosesNothing(): Boolean = false
            },
            diagnostics = StatusDiagnostics(log = {}, onIntentError = {}),
            reduction = Dispatchers.Default,
        )
    }

    /** Make [rig] the container the screen shows, retiring the previous one. */
    private fun ComposeUiTest.use(rig: Rig, predicate: (UiState) -> Boolean) {
        current?.scope?.cancel()
        shown = null
        current = rig
        val port = checkNotNull(port) { "the binding is entered before the tour" }
        rig.scope.launch {
            rig.host.screen.collect {
                port.show(it)
                shown = it
            }
        }
        awaitShown(predicate)
    }

    /** Wait for the screen to be shown a state matching [predicate], then for it to render it. */
    private fun ComposeUiTest.awaitShown(predicate: (UiState) -> Boolean) {
        try {
            waitUntil(timeoutMillis = WAIT_MS) { shown?.let(predicate) == true }
        } catch (timeout: ComposeTimeoutException) {
            // The contract runner reports a clause's failure by its message alone, so the step and what the screen
            // showed instead travel in it.
            val step = Throwable().stackTraceToString().lineSequence().drop(1).firstOrNull { SOURCE in it && "awaitShown" !in it }
            throw AssertionError("waited at ${step?.trim()}; the screen showed $shown", timeout)
        }
        waitForIdle()
    }

    /** Bring the screen up through the adapter's own entry, then touch every control, part by part. */
    private fun ComposeUiTest.tour() {
        use(Rig()) { it.layer is Layer.CreateEvent }
        val screen = content()
        val hosted = host
        setContent { hosted(screen) }
        waitForIdle()
        val parts = listOf<Pair<String, ComposeUiTest.() -> Unit>>(
            "update required" to { updateRequired() },
            "refused device" to { refusedDevice() },
            "create layer" to { createLayer() },
            "join gate" to { joinGate() },
            "joined" to { joined() },
            "permissions" to { permissions() },
        )
        for ((name, part) in parts) {
            tourLog.i { "tour: $name" }
            part()
        }
    }

    private val tourLog = Logger.withTag("contract")

    private fun ComposeUiTest.updateRequired() {
        use(Rig(refusal = VersionRefusal("0.4"))) { it.layer is Layer.UpdateRequired }
        onNodeWithText(str(Res.string.store_app_store)).performClick()
    }

    private fun ComposeUiTest.refusedDevice() {
        use(Rig(deviceRefusal = DeviceRefusal.DEVICE_UNVERIFIABLE)) {
            (it.layer as? Layer.CreateEvent)?.error == ScreenMessage.DEVICE_UNVERIFIABLE
        }
        val told = "${str(Res.string.message_device_unverifiable)} ${str(Res.string.message_report_this)}"
        onNodeWithText(told).performClick()
        awaitShown { it.overlays.reportingBug }
        onNodeWithText(str(Res.string.cancel)).performClick()
        awaitShown { !it.overlays.reportingBug }
    }

    private fun ComposeUiTest.createLayer() {
        use(Rig()) { it.layer is Layer.CreateEvent }
        // The hidden report: the double tap, Send, and the word on it tapped away.
        onNodeWithText("SNAPSYNC").performTouchInput { doubleClick() }
        awaitShown { it.overlays.reportingBug }
        onNodeWithText(str(Res.string.report_placeholder)).performTextInput("It froze")
        onNodeWithText(str(Res.string.report_send)).performClick()
        awaitShown { it.overlays.reportNotice == app.snapsync.ui.state.ReportNotice.SENT }
        onNodeWithText(str(Res.string.report_sent)).performClick()
        awaitShown { it.overlays.reportNotice == null }
        // The menu: its switch, its close, its report, its links.
        openMenu()
        onNodeWithText(str(Res.string.mobile_data_toggle)).performClick()
        onNodeWithContentDescription(str(ComponentRes.string.menu_close)).performClick()
        awaitShown { !it.overlays.menuOpen }
        openMenu()
        onNodeWithText(str(Res.string.report_problem)).performClick()
        awaitShown { it.overlays.reportingBug && !it.overlays.menuOpen }
        onNodeWithText(str(Res.string.cancel)).performClick()
        awaitShown { !it.overlays.reportingBug }
        openMenu()
        onNodeWithText(str(Res.string.menu_website)).performClick()
        awaitShown { !it.overlays.menuOpen }
        // The form: the name, every calendar gesture, every wheel — then Create.
        createForm()
        onNodeWithText(str(Res.string.create_button)).performClick()
        // A range already over: Create asks first how long others may join — closed once, then answered.
        pastRange()
        onNodeWithText(str(Res.string.create_button)).performClick()
        awaitShown { it.create?.form?.keepOpen != null }
        onNodeWithText(str(Res.string.cancel)).performClick()
        awaitShown { it.create?.form?.keepOpen == null }
        onNodeWithText(str(Res.string.create_button)).performClick()
        awaitShown { it.create?.form?.keepOpen != null }
        onNode(hasText("1 day", substring = true)).performClick()
        awaitShown { it.create?.form?.keepOpen == null }
    }

    /** The form moved onto Thursday 2 July — before the clock's Monday 6 July — 11:01 to 13:xx. */
    private fun ComposeUiTest.pastRange() {
        onNode(dateDescribed("Thursday", "2", "July", "2026")).performClick()
        awaitShown { it.create?.form?.picker?.let { p -> p.from.date == JULY_2 && p.endPending } == true }
        onNode(dateDescribed("Thursday", "2", "July", "2026")).performClick()
        awaitShown { it.create?.form?.picker?.let { p -> p.endDay == JULY_2 && !p.endPending } == true }
        onNodeWithContentDescription(str(ComponentRes.string.wheel_end_minute), useUnmergedTree = true)
            .performScrollTo()
            .performTouchInput {
                down(center)
                moveBy(Offset(0f, -height / 2f))
                up()
            }
        awaitShown { it.create?.form?.picker?.untilMinute != null }
        wheelRow(ComponentRes.string.wheel_end_hour, "13")
        awaitShown { it.create?.form?.canCreate == true }
    }

    private fun ComposeUiTest.openMenu() {
        onNodeWithContentDescription(str(ComponentRes.string.menu)).performClick()
        awaitShown { it.overlays.menuOpen }
    }

    /**
     * The create form through every control it has (the clock reads Monday 6 July 2026, 12:00): the name typed; the last
     * day dragged forward, the start dragged onto it, a new range swept; the 8th tapped, which starts a new one-day range;
     * the end's minutes dragged with its hour blank, then its hour tapped; the start's hour and minute tapped. What it
     * leaves is complete: Wednesday 8 July, 11:01 to 13:xx.
     */
    private fun ComposeUiTest.createForm() {
        onNode(hasSetTextAction()).performTextInput("My Party")
        awaitShown { it.create?.form?.name == "My Party" }
        onNode(dateDescribed("Monday", "6", "July", "2026")).performTouchInput { dragCells(1) }
        awaitShown { it.create?.form?.picker?.endDay == JULY_7 }
        // The start, forward onto the end: the 6th heads its row in a Monday-first calendar and July begins on a
        // Wednesday, so there is no earlier day beside or above it to drag back to.
        onNode(dateDescribed("Monday", "6", "July", "2026")).performTouchInput { dragCells(1) }
        awaitShown { it.create?.form?.picker?.let { p -> p.from.date == JULY_7 && p.endDay == JULY_7 } == true }
        onNode(dateDescribed("Friday", "10", "July", "2026")).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + PAST_LONG_PRESS_MS)
            moveBy(Offset(width.toFloat(), 0f))
            up()
        }
        awaitShown { it.create?.form?.picker?.endDay == JULY_11 }
        onNode(dateDescribed("Wednesday", "8", "July", "2026")).performClick()
        awaitShown { it.create?.form?.picker?.let { p -> p.from.date == JULY_8 && p.endDay == JULY_8 } == true }
        onNodeWithContentDescription(str(ComponentRes.string.wheel_end_minute), useUnmergedTree = true)
            .performScrollTo()
            .performTouchInput {
                down(center)
                moveBy(Offset(0f, -height / 2f))
                up()
            }
        awaitShown { it.create?.form?.picker?.untilMinute != null }
        wheelRow(ComponentRes.string.wheel_end_hour, "13")
        awaitShown { it.create?.form?.picker?.untilHour == END_HOUR }
        wheelRow(ComponentRes.string.wheel_start_hour, "11")
        awaitShown { it.create?.form?.picker?.from?.hour == START_HOUR }
        wheelRow(ComponentRes.string.wheel_start_minute, "01")
        awaitShown { it.create?.form?.canCreate == true }
    }

    /** Tap the [row] of the wheel [wheel] names. */
    private fun ComposeUiTest.wheelRow(wheel: org.jetbrains.compose.resources.StringResource, row: String) {
        onNode(
            hasText(row) and hasAnyAncestor(hasContentDescription(str(wheel))),
            useUnmergedTree = true,
        ).performClick()
    }

    /** A drag from the day pressed on, past the touch slop, [cells] days across. */
    private fun androidx.compose.ui.test.TouchInjectionScope.dragCells(cells: Int) {
        down(center)
        val direction = if (cells < 0) -1f else 1f
        moveBy(Offset((viewConfiguration.touchSlop + 2) * direction, 0f))
        moveBy(Offset(width * cells.toFloat(), 0f))
        up()
    }

    private val UiState.create get() = layer as? Layer.CreateEvent

    private companion object {
        val JULY_2 = kotlinx.datetime.LocalDate(2026, 7, 2)
        val JULY_7 = kotlinx.datetime.LocalDate(2026, 7, 7)
        val JULY_8 = kotlinx.datetime.LocalDate(2026, 7, 8)
        val JULY_11 = kotlinx.datetime.LocalDate(2026, 7, 11)
        const val END_HOUR = 13
        const val START_HOUR = 11
        const val PAST_LONG_PRESS_MS = 100L
    }

    /** A calendar day, whatever order the device's locale writes its date in. */
    private fun dateDescribed(vararg words: String) = SemanticsMatcher("a day described by ${words.toList()}") { node ->
        node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { description ->
            val parts = description.split(' ', ',').filter { it.isNotBlank() }
            words.all { it in parts }
        }
    }

    private val ready: (UiState) -> Boolean =
        { (it.joining?.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.Ready }

    private val UiState.joined get() = layer as? Layer.Joined
    private val UiState.joining get() = layer as? Layer.JoiningEvent

    private fun ComposeUiTest.joinGate() {
        var loads = 0
        val rig = Rig(details = { if (++loads == 1) JoinLoad.Failed() else OTHER_EVENT })
        use(rig) { it.layer is Layer.CreateEvent }
        // A failed load, retried; then the gate cancelled.
        rig.host.onOpenUrl(linkTo(OTHER_ID))
        awaitShown { it.joining?.phase == JoinPhase.LoadFailed }
        onNodeWithText(str(Res.string.retry)).performClick()
        awaitShown(ready)
        onNodeWithText(str(Res.string.cancel)).performClick()
        awaitShown { it.layer is Layer.CreateEvent }
        // The gate again: every edit of its form, then a commit that fails and is retried.
        rig.host.onOpenUrl(linkTo(OTHER_ID))
        awaitShown(ready)
        // The calendar opened (Custom) and closed unchanged; the quick choices are chips on the screen.
        onNodeWithText(str(ComponentRes.string.share_range_custom)).performClick()
        awaitShown { it.joining?.range?.picker != null }
        onAllNodesWithText(str(Res.string.cancel)).onLast().performClick()
        awaitShown { it.joining?.range?.picker == null }
        onNodeWithText(str(ComponentRes.string.share_range_from_now)).performClick()
        awaitShown { it.joining?.range?.preset == RangeChoice.FROM_NOW }
        onNodeWithText(str(ComponentRes.string.share_range_custom)).performClick()
        awaitShown { it.joining?.range?.picker != null }
        onNodeWithText(str(Res.string.save)).performClick()
        awaitShown { it.joining?.range?.preset == RangeChoice.CUSTOM }
        onNodeWithText(str(ComponentRes.string.share_range_event_start)).performClick()
        awaitShown { it.joining?.range?.preset == RangeChoice.EVENT_START }
        onNodeWithText(str(Res.string.share_toggle)).performScrollTo().performClick()
        awaitShown { it.joining?.range?.shareOn == false }
        onNodeWithText(str(Res.string.receive_toggle)).performScrollTo().performClick()
        awaitShown { it.joining?.range?.receiveOn == false }
        onNodeWithText(str(Res.string.receive_toggle)).performScrollTo().performClick()
        awaitShown { it.joining?.range?.receiveOn == true }
        onNodeWithText(str(Res.string.album_toggle)).performScrollTo().performClick()
        awaitShown { it.joining?.range?.saveToAlbum == false }
        onNodeWithText(str(Res.string.join_button)).performClick()
        awaitShown { (it.joining?.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.CommitFailed }
        onNodeWithText(str(Res.string.retry)).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.joined() {
        val rig = Rig(config = MEMBERSHIP)
        use(rig) { it.joined != null }
        // Leave's confirmation, dismissed.
        onNodeWithText(str(Res.string.leave_event)).performClick()
        awaitShown { it.overlays.confirmingLeave }
        onNodeWithText(str(Res.string.leave_cancel)).performClick()
        awaitShown { !it.overlays.confirmingLeave }
        // The invite, shared and shown as a QR code that is tapped away above the sheet (the screen suites swipe it).
        onNodeWithText(str(Res.string.share_invite)).performClick()
        onNodeWithText(str(Res.string.show_qr)).performClick()
        awaitShown { it.overlays.showingQr }
        onNodeWithContentDescription("Close sheet").performTouchInput { click(Offset(centerX, ABOVE_THE_SHEET_PX)) }
        awaitShown { !it.overlays.showingQr }
        // The rename sheet, cancelled, then saved.
        onNodeWithContentDescription(str(Res.string.rename_event)).performClick()
        awaitShown { it.overlays.renaming }
        onNodeWithText(str(Res.string.cancel)).performClick()
        awaitShown { !it.overlays.renaming }
        onNodeWithContentDescription(str(Res.string.rename_event)).performClick()
        awaitShown { it.overlays.renaming }
        onNode(hasSetTextAction()).performTextClearance()
        onNode(hasSetTextAction()).performTextInput("Anna’s Party")
        onNodeWithText(str(Res.string.save)).performClick()
        waitForIdle()
        // No rename status arrives over these cells, so the sheet stays up after Save: close it.
        if (rig.host.screen.value.overlays.renaming) onNodeWithText(str(Res.string.cancel)).performClick()
        awaitShown { !it.overlays.renaming }
        // The event's settings: stopping sharing asked about, kept, asked again and confirmed; then closed.
        onNodeWithText(str(Res.string.footer_settings)).performClick()
        awaitShown { it.joined?.surface is JoinedSurface.Reconfigure }
        onNodeWithText(str(Res.string.share_toggle)).performClick()
        awaitShown { (it.joined?.surface as? JoinedSurface.Reconfigure)?.askingToStopSharing == true }
        onNodeWithText(str(Res.string.stop_sharing_keep)).performClick()
        awaitShown { (it.joined?.surface as? JoinedSurface.Reconfigure)?.askingToStopSharing == false }
        onNodeWithText(str(Res.string.share_toggle)).performClick()
        awaitShown { (it.joined?.surface as? JoinedSurface.Reconfigure)?.askingToStopSharing == true }
        onNode(hasText(str(Res.string.stop_sharing_confirm)) and hasAnyAncestor(isDialog())).performClick()
        awaitShown { (it.joined?.surface as? JoinedSurface.Reconfigure)?.askingToStopSharing == false }
        onNodeWithContentDescription("Close sheet").performTouchInput { click(Offset(centerX, ABOVE_THE_SHEET_PX)) }
        awaitShown { it.joined != null && it.joined?.surface !is JoinedSurface.Reconfigure }
        // A different event's link while joined: the switch, cancelled.
        rig.host.onOpenUrl(linkTo(OTHER_ID))
        awaitShown { it.joined?.pendingSwitch != null }
        onNodeWithText(str(Res.string.cancel)).performClick()
        awaitShown { it.joined != null && it.joined?.pendingSwitch == null }
        // Leave, confirmed.
        onNodeWithText(str(Res.string.leave_event)).performClick()
        awaitShown { it.overlays.confirmingLeave }
        onNode(hasText(str(Res.string.leave_confirm)) and hasAnyAncestor(isDialog())).performClick()
        awaitShown { it.joined == null }
    }

    private fun ComposeUiTest.permissions() {
        // Never asked: the prompt requests access; then a switch confirmed.
        val asked = Rig(config = MEMBERSHIP, permission = GalleryAccess.NOT_DETERMINED)
        use(asked) { it.joined != null }
        onNodeWithText(str(ComponentRes.string.status_allow_access)).performClick()
        asked.host.onOpenUrl(linkTo(OTHER_ID))
        awaitShown { it.joined?.pendingSwitch != null }
        onNodeWithText(str(Res.string.switch_confirm)).performClick()
        awaitShown { it.joining?.eventId == OTHER_ID }
        // Denied: the prompt opens Settings.
        use(Rig(config = MEMBERSHIP, permission = GalleryAccess.DENIED)) { it.joined != null }
        onNodeWithText(str(ComponentRes.string.status_allow_access_settings)).performClick()
        // Partial: the picker.
        use(Rig(config = MEMBERSHIP, permission = GalleryAccess.LIMITED)) { it.joined?.canChoosePhotos == true }
        onNodeWithText(str(Res.string.choose_more_photos)).performClick()
        onNodeWithText(str(Res.string.allow_full_access)).performClick()
        waitForIdle()
    }
}
