@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.domain.version.VersionRefusal
import app.snapsync.feature.creation.readmodel.CreationStatus
import app.snapsync.feature.status.readmodel.NetworkStatusSource
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.ForegroundReturn
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
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.DeviceVerification
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.StatusDiagnostics
import app.snapsync.presentation.StatusSources
import app.snapsync.presentation.onIntent
import app.snapsync.ui.components.LocalReduceMotion
import app.snapsync.ui.components.resources.wheel_end_hour
import app.snapsync.ui.components.resources.wheel_end_minute
import app.snapsync.ui.state.CreateStep
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import app.snapsync.ui.components.resources.Res as ComponentRes

/** The store page every rig's build carries. */
internal const val RIG_STORE_URL = "https://apps.apple.com/de/app/snapsync/id0000000000"

/** A clock a test can move. */
internal class TestClock(var instant: Instant = Instant.parse("2026-07-06T12:00:00Z"))

/**
 * A real status container over plain cells, recording every command it fires — what a screen test composes the real
 * screen over when what it asserts is the container's answer to a tap (the tap → intent table, the create form).
 */
@Suppress("LongParameterList")
internal class ContainerRig(
    config: EventConfig? = null,
    permission: GalleryAccess = GalleryAccess.GRANTED,
    refusal: VersionRefusal? = null,
    private val details: suspend (String) -> JoinLoad = { error("no details here") },
    network: NetworkAccess = NetworkAccess.Online(restricted = false),
    deviceRefusal: DeviceRefusal? = null,
    val clock: TestClock = TestClock(),
    val creation: MutableStateFlow<CreationStatus> = MutableStateFlow(CreationStatus.Idle),
    val foreground: MutableStateFlow<ForegroundReturn> = MutableStateFlow(ForegroundReturn.NONE),
) {
    val config = MutableStateFlow(config)
    val permission = MutableStateFlow(permission)
    private val firedCell = MutableStateFlow<List<String>>(emptyList())
    val fired: List<String> get() = firedCell.value
    private val createdCell = MutableStateFlow<List<Triple<String, EventStart, EventEnd>>>(emptyList())

    /** Every create the container fired: the name and the window, as the command carries them. */
    val created: List<Triple<String, EventStart, EventEnd>> get() = createdCell.value
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun record(what: String) = firedCell.update { it + what }

    val host = StatusContainerHost(
        StatusSources(
            sync = object : SyncStatusSource {
                override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Loading)
            },
            permission = this.permission,
            config = this.config,
            creation = creation,
            versionRefusal = MutableStateFlow(refusal),
            verification = DeviceVerification(refusal = MutableStateFlow(deviceRefusal)),
            store = StoreLink(RIG_STORE_URL, StoreKind.APP_STORE),
            network = object : NetworkStatusSource {
                override val access: StateFlow<NetworkAccess> = MutableStateFlow(network)
                override val returned: Flow<Unit> = emptyFlow()
            },
            foreground = foreground,
        ),
        scope = scope,
        cutoffFormatter = CutoffFormatter(now = { clock.instant }, zone = TimeZone.UTC),
        commands = object : UserCommands {
            override suspend fun leave() {
                record("leave")
                this@ContainerRig.config.value = null
            }

            override fun create(name: String, startsAt: EventStart, endsAt: EventEnd, openUntil: OpenUntil?) {
                record("create:$name${openUntil?.let { ":open-until:${TimestampText.format(it.at)}" }.orEmpty()}")
                createdCell.update { it + Triple(name, startsAt, endsAt) }
            }

            override suspend fun commitJoin(choice: JoinChoice): JoinCommit {
                record("commitJoin:${choice.eventId}:${choice.direction}:${choice.saveToAlbum}")
                return JoinCommit.Failed
            }

            override fun share(url: String, title: String) = record("share:$url")

            override fun requestAccess() = record("requestAccess")

            override fun openSettings() = record("openSettings")

            override fun openLink(url: String) = record("openLink:$url")

            override fun choosePhotos() = record("choosePhotos")

            override suspend fun reconfigure(
                eventId: EventId,
                direction: Direction,
                minPhotoDate: CaptureCutoff,
                maxPhotoDate: CaptureCeiling,
                saveToAlbum: Boolean,
            ): ReconfigureOutcome {
                record("reconfigure:$eventId:$direction")
                return ReconfigureOutcome.Saved
            }

            override fun rename(eventId: EventId, name: String) = record("rename:$eventId:$name")

            override suspend fun resetRename() = record("resetRename")

            override suspend fun sendDiagnostics(note: String, context: ReportContext): ReportOutcome {
                // A report opened from "Report this" is marked: only it carries the refused verification's facts.
                record("sendDiagnostics:$note" + if (context.verification) " [verification]" else "")
                return ReportOutcome.SENT
            }

            override suspend fun setMobileData(on: Boolean): Boolean {
                record("setMobileData:$on")
                return true
            }

            override suspend fun restoreEventKey(linkKey: String): Boolean {
                record("restoreEventKey")
                return false
            }
        },
        queries = object : UserQueries {
            override suspend fun loadJoinDetails(eventId: EventId, linkKey: String?) = details(eventId.value)

            override suspend fun shareableCount(cutoff: CaptureCutoff, until: CaptureCeiling?): Int? = null

            override suspend fun switchLosesNothing(): Boolean = false
        },
        diagnostics = StatusDiagnostics(log = {}, onIntentError = {}),
        reduction = Dispatchers.Default,
    )

    val state: UiState get() = host.screen.value
}

/** Compose [rig]'s real screen with the shared factory's bundle, as every host does. */
internal fun ComposeUiTest.show(rig: ContainerRig) {
    setContent {
        CompositionLocalProvider(LocalReduceMotion provides true) {
            val state by rig.host.screen.collectAsState()
            WithoutKeyboardInsets {
                StatusScreen(state = state, dateFormats = testDates, actions = statusActions(rig.host::onIntent))
            }
        }
    }
}

/** Run [block] on [rig]'s screen, cancelling its scope however the test ends. */
internal fun rigTest(rig: ContainerRig, block: ComposeUiTest.(ContainerRig) -> Unit) = runComposeUiTest {
    try {
        show(rig)
        block(rig)
    } finally {
        rig.scope.cancel()
    }
}

/** Wait for the container to reduce to a state matching [predicate], then for the screen to catch up. */
internal fun ComposeUiTest.awaitState(rig: ContainerRig, predicate: (UiState) -> Boolean) {
    waitUntil(timeoutMillis = 5_000) { predicate(rig.state) }
    waitForIdle()
}

/** Wait for the container to fire [command] — commands run in the container's intents, off this thread. */
internal fun ComposeUiTest.awaitFired(rig: ContainerRig, command: String) {
    waitUntil(timeoutMillis = 5_000) { command in rig.fired }
}

/** The create layer the state shows, if it shows one. */
internal val UiState.create get() = layer as? Layer.CreateEvent

internal fun ComposeUiTest.typeName(rig: ContainerRig, name: String) {
    onNode(hasSetTextAction()).performTextInput(name)
    awaitState(rig) { it.create?.form?.name == name }
}

internal fun ComposeUiTest.pickDay(rig: ContainerRig, description: String, day: Int) {
    onNodeWithContentDescription(description).performClick()
    awaitState(rig) { it.create?.form?.picker?.endDay?.day == day }
}

/**
 * Set the end time to [row]:00 — the Until hour by tapping a row next to its reading line (the start's hour,
 * 12), then the minute by tapping its blank reading line (which sits over the start's :00). The hour alone
 * never sets the end.
 */
internal fun ComposeUiTest.setUntilHour(rig: ContainerRig, row: String) {
    // The wheels sit below the calendar, under the fold of a test window: scroll the FORM (the wheel's own closest
    // scroll parent) so the wheel is in view, then tap the row.
    onNodeWithContentDescription(str(ComponentRes.string.wheel_end_hour), useUnmergedTree = true).performScrollTo()
    onNode(
        hasText(row) and hasAnyAncestor(hasContentDescription(str(ComponentRes.string.wheel_end_hour))),
        useUnmergedTree = true,
    ).performClick()
    awaitState(rig) { it.create?.form?.picker?.untilHour == row.toInt() }
    onNode(
        hasText("--") and hasAnyAncestor(hasContentDescription(str(ComponentRes.string.wheel_end_minute))),
        useUnmergedTree = true,
    ).performClick()
    awaitState(rig) { it.create?.form?.nextStep !is CreateStep.EndTime }
}

/** A complete form: [name], the last day Wednesday 8 July, and the end at 13:00. */
internal fun ComposeUiTest.completeForm(rig: ContainerRig, name: String) {
    typeName(rig, name)
    pickDay(rig, "Wednesday, 8 July 2026", day = 8)
    setUntilHour(rig, "13")
}
