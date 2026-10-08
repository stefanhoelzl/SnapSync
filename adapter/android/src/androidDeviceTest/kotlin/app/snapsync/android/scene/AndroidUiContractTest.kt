@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.android.scene

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
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
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import app.snapsync.android.dates.AndroidDateFormatting
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.UiContract
import app.snapsync.contracts.UiStateForContract
import app.snapsync.contracts.UiUnderTest
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.feature.status.readmodel.NetworkStatusSource
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.DeviceRefusal
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.EventEnd
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.EventStart
import app.snapsync.model.GalleryAccess
import app.snapsync.model.JoinChoice
import app.snapsync.model.JoinCommit
import app.snapsync.model.JoinLoad
import app.snapsync.model.JoinPhase
import app.snapsync.model.JoinedSurface
import app.snapsync.model.Layer
import app.snapsync.model.NetworkAccess
import app.snapsync.model.RangeChoice
import app.snapsync.model.ReconfigureOutcome
import app.snapsync.model.ReportContext
import app.snapsync.model.ReportOutcome
import app.snapsync.model.ScreenMessage
import app.snapsync.model.StoreKind
import app.snapsync.model.StoreLink
import app.snapsync.model.SyncStatus
import app.snapsync.model.UiState
import app.snapsync.model.UserCommands
import app.snapsync.model.UserQueries
import app.snapsync.model.VersionRefusal
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.encodeEventUrl
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import app.snapsync.ports.Ui
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.DeviceVerification
import app.snapsync.presentation.ScreenDates
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.StatusDiagnostics
import app.snapsync.presentation.StatusSources
import app.snapsync.presentation.onIntent
import app.snapsync.ui.components.resources.menu
import app.snapsync.ui.components.resources.menu_close
import app.snapsync.ui.components.resources.share_range_change
import app.snapsync.ui.components.resources.status_allow_access
import app.snapsync.ui.components.resources.status_allow_access_settings
import app.snapsync.ui.components.resources.wheel_end_hour
import app.snapsync.ui.components.resources.wheel_end_minute
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.album_toggle
import app.snapsync.ui.resources.allow_full_access
import app.snapsync.ui.resources.cancel
import app.snapsync.ui.resources.choose_more_photos
import app.snapsync.ui.resources.create_button
import app.snapsync.ui.resources.footer_settings
import app.snapsync.ui.resources.invite_caption
import app.snapsync.ui.resources.join_button
import app.snapsync.ui.resources.leave_cancel
import app.snapsync.ui.resources.leave_confirm
import app.snapsync.ui.resources.leave_event
import app.snapsync.ui.resources.menu_website
import app.snapsync.ui.resources.message_device_unverifiable
import app.snapsync.ui.resources.message_report_this
import app.snapsync.ui.resources.mobile_data_toggle
import app.snapsync.ui.resources.range_from_now
import app.snapsync.ui.resources.range_whole_event
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
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import app.snapsync.ui.components.resources.Res as ComponentRes

/** The event the joined parts of the tour are members of. */
private const val JOINED_ID = "11111111-1111-4111-8111-111111111111"

/** A second event, for the join gate and the switch. */
private const val OTHER_ID = "22222222-2222-4222-8222-222222222222"

private const val STORE_URL = "https://apps.apple.com/de/app/snapsync/id0000000000"

private const val WAIT_MS = 10_000L

/** A tap just inside the top of the sheet's scrim: above the sheet, where a tap closes it. */
private const val ABOVE_THE_SHEET_PX = 5f

/** The activity's UI test, which runs the whole contract: past `runTest`'s default minute on a software-rendering emulator. */
private val TOUR_TIMEOUT = 10.minutes

private val MEMBERSHIP = EventConfig(
    eventId = JOINED_ID,
    name = "Anna’s Birthday",
    minPhotoDate = captureCutoff("2026-07-04T18:00:00Z"),
    startsAt = eventStart("2026-07-04T18:00:00Z"),
    endsAt = eventEnd("2026-07-20T18:00:00Z"),
    maxPhotoDate = captureCeiling("2026-07-20T18:00:00Z"),
    deletesAt = deletesAt("2099-12-31T00:00:00Z"),
)

private val OTHER_EVENT = JoinLoad.Found(
    "Anna’s Wedding",
    eventStart("2026-07-04T18:00:00Z"),
    eventEnd("2026-07-20T18:00:00Z"),
    deletesAt("2026-08-03T18:00:00Z"),
)

private val CUTOFF = CutoffFormatter(now = { Instant.parse("2026-07-06T12:00:00Z") }, zone = TimeZone.UTC)

private fun linkTo(eventId: String) = encodeEventUrl(EventLinkPayload(eventId))

// A test reads the words the screen shows; production never blocks on a resource.
private fun str(res: StringResource): String = runBlocking { getString(res) }

/**
 * [AndroidUi] against the [UiContract]: the activity's pull of the screen ([AndroidUi.content]) inside a Compose UI test
 * on the device, every control tapped through the real status screen. What the screen shows is a real
 * [StatusContainerHost]'s state, pushed through [AndroidUi.show]; each intent the clause's handler receives is reduced by
 * that container, so a tap that opens a dialog really opens it. The tour swaps the container between parts — a
 * membership, a refusal, a grant — but never the [AndroidUi], so every tap reaches the one handler the clause listened
 * with.
 */
class AndroidUiContractTest {

    private val ui = AndroidUi(CUTOFF, ScreenDates(AndroidDateFormatting()::formats), Logger.withTag("contract"))

    /**
     * [ui] as the clause's call log sees it: what the tour shows each state through, since showing is the clause's own
     * act. The activity's pull of the screen ([AndroidUi.content]) is no port call, so it stays on [ui].
     */
    @Volatile
    private var port: Ui = ui

    /** The container the screen currently shows; [react] reduces into it. */
    @Volatile
    private var current: Rig? = null

    /** The last state [port] was shown — what the screen renders once it is idle. */
    @Volatile
    private var shown: UiState? = null

    private val binding = object : Binding<UiStateForContract, UiUnderTest> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(UiStateForContract.TOURABLE)
        override fun create(state: UiStateForContract, clauseId: String, log: CallLog): Entered<UiUnderTest> {
            port = ui.recorded(log)
            return Entered.Ready(
                UiUnderTest(
                    ui = port,
                    react = { intent -> current?.host?.onIntent(intent) },
                    tour = {
                        checkNotNull(screenTest) { "the tour runs inside the activity's UI test" }.tour()
                    },
                ),
                dispose = { current?.scope?.cancel() },
            )
        }
    }

    @Test
    fun `the status screen satisfies the Ui contract`() =
        // The activity is launched before the clause runs, so the clause's own minute is spent on the tour alone.
        runAndroidComposeUiTest<ComponentActivity>(testTimeout = TOUR_TIMEOUT) {
            screenTest = this
            try {
                verify(UiContract, binding)
            } finally {
                screenTest = null
                current?.scope?.cancel()
            }
        }

    /** The UI test hosting the activity the tour brings the screen up in. */
    private var screenTest: ComposeUiTest? = null

    /** A real container over plain cells, its every state shown through [ui]. */
    private inner class Rig(
        config: EventConfig? = null,
        permission: GalleryAccess = GalleryAccess.GRANTED,
        refusal: VersionRefusal? = null,
        deviceRefusal: DeviceRefusal? = null,
        private val details: suspend (String) -> JoinLoad = { OTHER_EVENT },
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

                override fun create(name: String, startsAt: EventStart, endsAt: EventEnd) = Unit

                override suspend fun commitJoin(choice: JoinChoice) = JoinCommit.Failed

                override fun share(url: String, title: String) = Unit

                override fun requestAccess() = Unit

                override fun openSettings() = Unit

                override fun openLink(url: String) = Unit

                override fun choosePhotos() = Unit

                override suspend fun reconfigure(
                    eventId: String,
                    direction: Direction,
                    minPhotoDate: CaptureCutoff,
                    maxPhotoDate: CaptureCeiling,
                    saveToAlbum: Boolean,
                ) = ReconfigureOutcome.Saved

                override fun rename(eventId: String, name: String) = Unit

                override suspend fun resetRename() = Unit

                override suspend fun sendDiagnostics(note: String, context: ReportContext) = ReportOutcome.SENT

                override suspend fun setMobileData(on: Boolean) = true

                override suspend fun restoreEventKey(linkKey: String) = false
            },
            queries = object : UserQueries {
                override suspend fun loadJoinDetails(eventId: String, linkKey: String?) = details(eventId)

                override suspend fun shareableCount(cutoff: CaptureCutoff, until: CaptureCeiling?): Int? = null
            },
            diagnostics = StatusDiagnostics(log = {}, onIntentError = {}),
        )
    }

    /** Make [rig] the container the screen shows, retiring the previous one. */
    private fun ComposeUiTest.use(rig: Rig, predicate: (UiState) -> Boolean) {
        current?.scope?.cancel()
        shown = null
        current = rig
        rig.scope.launch {
            rig.host.container.stateFlow.collect {
                port.show(it)
                shown = it
            }
        }
        awaitShown(predicate)
    }

    /** Wait for the screen to be shown a state matching [predicate], then for it to render it. */
    private fun ComposeUiTest.awaitShown(predicate: (UiState) -> Boolean) {
        waitUntil(timeoutMillis = WAIT_MS) { shown?.let(predicate) == true }
        waitForIdle()
    }

    /** Bring the screen up through the adapter's own entry, then touch every control, part by part. */
    private fun ComposeUiTest.tour() {
        use(Rig()) { it.layer is Layer.CreateEvent }
        val screen = ui.content()
        setContent {
            // As the screen suites do: the emulator's soft keyboard would otherwise shrink the form under a tap.
            Box(Modifier.consumeWindowInsets(WindowInsets.ime)) { screen() }
        }
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
        awaitShown { it.overlays.reportNotice == ReportOutcome.SENT }
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
        // Create, once the form is complete.
        completeForm("My Party")
        onNodeWithText(str(Res.string.create_button)).performClick()
    }

    private fun ComposeUiTest.openMenu() {
        onNodeWithContentDescription(str(ComponentRes.string.menu)).performClick()
        awaitShown { it.overlays.menuOpen }
    }

    /** A complete create form: a name, the last day Wednesday 8 July, and the end at 13:00. */
    private fun ComposeUiTest.completeForm(name: String) {
        onNode(hasSetTextAction()).performTextInput(name)
        onNode(dateDescribed("Wednesday", "8", "July", "2026")).performClick()
        onNodeWithContentDescription(str(ComponentRes.string.wheel_end_hour), useUnmergedTree = true).performScrollTo()
        onNode(
            hasText("13") and hasAnyAncestor(hasContentDescription(str(ComponentRes.string.wheel_end_hour))),
            useUnmergedTree = true,
        ).performClick()
        waitForIdle()
        onNode(
            hasText("--") and hasAnyAncestor(hasContentDescription(str(ComponentRes.string.wheel_end_minute))),
            useUnmergedTree = true,
        ).performClick()
        waitForIdle()
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
        val rig = Rig(details = { if (++loads == 1) JoinLoad.Failed else OTHER_EVENT })
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
        onNodeWithContentDescription(str(ComponentRes.string.share_range_change)).performClick()
        onNodeWithText(str(Res.string.range_from_now)).performClick()
        awaitShown { it.joining?.form?.preset == RangeChoice.FROM_NOW }
        onNodeWithContentDescription(str(ComponentRes.string.share_range_change)).performClick()
        onNodeWithText(str(Res.string.save)).performClick()
        awaitShown { it.joining?.form?.preset == RangeChoice.CUSTOM }
        onNodeWithContentDescription(str(ComponentRes.string.share_range_change)).performClick()
        onNode(hasText(str(Res.string.range_whole_event)) and isSelectable()).performClick()
        awaitShown { it.joining?.form?.preset == RangeChoice.WHOLE_EVENT }
        onNodeWithText(str(Res.string.share_toggle)).performScrollTo().performClick()
        awaitShown { it.joining?.form?.shareOn == false }
        onNodeWithText(str(Res.string.receive_toggle)).performScrollTo().performClick()
        awaitShown { it.joining?.form?.receiveOn == false }
        onNodeWithText(str(Res.string.receive_toggle)).performScrollTo().performClick()
        awaitShown { it.joining?.form?.receiveOn == true }
        onNodeWithText(str(Res.string.album_toggle)).performScrollTo().performClick()
        awaitShown { it.joining?.form?.saveToAlbum == false }
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
        // The invite, shared and shown as a QR code that is swiped away.
        onNodeWithText(str(Res.string.share_invite)).performClick()
        onNodeWithText(str(Res.string.show_qr)).performClick()
        awaitShown { it.overlays.showingQr }
        onNodeWithText(str(Res.string.invite_caption)).performTouchInput { swipeDown() }
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
        if (rig.host.container.stateFlow.value.overlays.renaming) onNodeWithText(str(Res.string.cancel)).performClick()
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
