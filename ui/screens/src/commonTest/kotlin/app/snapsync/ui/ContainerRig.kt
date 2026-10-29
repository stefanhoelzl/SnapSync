@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
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
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.membership.InviteFact
import app.snapsync.domain.membership.JoinChoice
import app.snapsync.domain.membership.JoinCommit
import app.snapsync.domain.membership.SettingsResult
import app.snapsync.domain.membership.ShareChoice
import app.snapsync.domain.membership.SwitchVerdict
import app.snapsync.domain.request.RequestId
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.domain.version.VersionRefusal
import app.snapsync.feature.creation.readmodel.CreationStatus
import app.snapsync.feature.status.readmodel.NetworkStatusSource
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.format.TimestampText
import app.snapsync.model.EventConfig
import app.snapsync.model.ForegroundReturn
import app.snapsync.model.GalleryAccess
import app.snapsync.model.NetworkAccess
import app.snapsync.model.ReportContext
import app.snapsync.model.ReportOutcome
import app.snapsync.model.Store
import app.snapsync.model.SyncStatus
import app.snapsync.model.UserCommands
import app.snapsync.model.UserQueries
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.DeviceVerification
import app.snapsync.presentation.RequestStatusFlows
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.StatusDiagnostics
import app.snapsync.presentation.StatusSources
import app.snapsync.presentation.onIntent
import app.snapsync.query.RequestStatuses
import app.snapsync.query.membership.JoinDetails
import app.snapsync.query.membership.JoinDetailsQuery
import app.snapsync.query.membership.MembershipStatuses
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
    private val details: suspend (String) -> JoinDetails = { error("no details here") },
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

    /** What the rig answers the membership's requests on — at once, as a core that settles every one does. */
    private val statuses = MembershipStatuses()
    private var lastRequest = 0L

    private fun <T> answered(on: RequestStatuses<T>, answer: T?): RequestId = RequestId(++lastRequest).also { id ->
        on.begun(id)
        answer?.let { on.done(id, it) }
    }

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
            store = Store("App Store", RIG_STORE_URL),
            network = object : NetworkStatusSource {
                override val access: StateFlow<NetworkAccess> = MutableStateFlow(network)
                override val returned: Flow<Unit> = emptyFlow()
            },
            foreground = foreground,
            requests = RequestStatusFlows(
                join = statuses.join.status,
                settings = statuses.settings.status,
                switch = statuses.switch.status,
                rename = statuses.rename.status,
            ),
        ),
        scope = scope,
        cutoffFormatter = CutoffFormatter(now = { clock.instant }, zone = TimeZone.UTC),
        commands = object : UserCommands {
            override fun leave() {
                record("leave")
                this@ContainerRig.config.value = null
            }

            override suspend fun create(name: String, startsAt: EventStart, endsAt: EventEnd, openUntil: OpenUntil?) {
                record("create:$name${openUntil?.let { ":open-until:${TimestampText.format(it.at)}" }.orEmpty()}")
                createdCell.update { it + Triple(name, startsAt, endsAt) }
            }

            override fun commitJoin(choice: JoinChoice): RequestId {
                record("commitJoin:${choice.event.id}:${choice.direction}:${choice.saveToAlbum}")
                return answered(statuses.join, JoinCommit.Failed())
            }

            override suspend fun share(url: String, title: String) = record("share:$url")

            override suspend fun requestAccess() = record("requestAccess")

            override suspend fun openSettings() = record("openSettings")

            override suspend fun openLink(url: String) = record("openLink:$url")

            override suspend fun choosePhotos() = record("choosePhotos")

            override fun reconfigure(choice: ShareChoice): RequestId {
                record("reconfigure:${choice.event}:${choice.direction}")
                return answered(statuses.settings, SettingsResult.Saved)
            }

            override fun rename(eventId: EventId, name: String): RequestId {
                record("rename:$eventId:$name")
                return answered(statuses.rename, null)
            }

            override fun switch(invite: InviteFact): RequestId = answered(statuses.switch, SwitchVerdict.Ask)

            override suspend fun sendDiagnostics(note: String, context: ReportContext): ReportOutcome {
                // A report opened from "Report this" is marked: only it carries the refused verification's facts.
                record("sendDiagnostics:$note" + if (context.verification) " [verification]" else "")
                return ReportOutcome.SENT
            }

            override suspend fun setMobileData(on: Boolean): Boolean {
                record("setMobileData:$on")
                return true
            }

            override fun restoreKey(key: EventKey): RequestId {
                record("restoreKey")
                return answered(statuses.restore, null)
            }
        },
        queries = object : UserQueries {
            override suspend fun shareableCount(range: CaptureRange): Int? = null
        },
        joinDetails = object : JoinDetailsQuery {
            override suspend fun load(invite: InviteLink): JoinDetails = details(invite.event.value)
        },
        diagnostics = StatusDiagnostics(log = {}, onIntentError = {}),
        reduction = Dispatchers.Default,
    )

    val state: UiState get() = host.screen.value

    private val composedCell = MutableStateFlow<UiState?>(null)

    /**
     * The state the screen last composed — what a tap lands on. It trails [state]: the reduction publishes on
     * another thread, and the screen catches up only once its collector is resumed and a frame composes.
     */
    val composed: UiState? get() = composedCell.value

    /** The container's screen state, collected for a screen to compose — each one it composes recorded as [composed]. */
    @Composable
    fun collectScreen(): UiState {
        val state by host.screen.collectAsState()
        SideEffect { composedCell.value = state }
        return state
    }
}

/** Compose [rig]'s real screen with the shared factory's bundle, as every host does. */
internal fun ComposeUiTest.show(rig: ContainerRig) {
    setContent {
        CompositionLocalProvider(LocalReduceMotion provides true) {
            val state = rig.collectScreen()
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

/**
 * Wait for the screen to compose a state matching [predicate], then for it to settle. The screen's state, not the
 * container's: the container publishes on another thread, so a state it has reduced may not be on screen yet, and a
 * tap that follows would look for a node the screen has not drawn.
 */
internal fun ComposeUiTest.awaitState(rig: ContainerRig, predicate: (UiState) -> Boolean) {
    waitUntil(timeoutMillis = 5_000) { rig.composed?.let(predicate) == true }
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
