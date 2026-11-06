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
import app.snapsync.domain.album.AlbumChoice
import app.snapsync.domain.album.AlbumContents
import app.snapsync.domain.event.CreateResult
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.Event
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventName
import app.snapsync.domain.event.EventNameVerdict
import app.snapsync.domain.event.EventRange
import app.snapsync.domain.event.EventRangeVerdict
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.foreground.ForegroundReturn
import app.snapsync.domain.foreground.InFront
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.domain.membership.JoinCommit
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.SettingsResult
import app.snapsync.domain.membership.Share
import app.snapsync.domain.membership.SwitchVerdict
import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.domain.mobiledata.MobileDataOutcome
import app.snapsync.domain.network.NetworkAccess
import app.snapsync.domain.report.BuildLabel
import app.snapsync.domain.report.ReportDestination
import app.snapsync.domain.report.ReportOutcome
import app.snapsync.domain.request.AppLinkRequest
import app.snapsync.domain.request.CreateRequest
import app.snapsync.domain.request.JoinRequest
import app.snapsync.domain.request.KeyRestoreRequest
import app.snapsync.domain.request.MembershipReadRequest
import app.snapsync.domain.request.MobileDataRequest
import app.snapsync.domain.request.RenameRequest
import app.snapsync.domain.request.ReportRequest
import app.snapsync.domain.request.RequestId
import app.snapsync.domain.request.SettingsRequest
import app.snapsync.domain.request.ShareRequest
import app.snapsync.domain.request.SwitchRequest
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.domain.version.Store
import app.snapsync.domain.version.VersionRefusal
import app.snapsync.format.InviteLinkText
import app.snapsync.format.TimestampText
import app.snapsync.inbound.Background
import app.snapsync.inbound.Completion
import app.snapsync.inbound.InboundImplementation
import app.snapsync.presentation.BuildFacts
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.DeviceVerification
import app.snapsync.presentation.EventKeyView
import app.snapsync.presentation.PlatformCommands
import app.snapsync.presentation.RequestStatusFlows
import app.snapsync.presentation.ScreenQueries
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.UserCommand
import app.snapsync.presentation.UserCommands
import app.snapsync.presentation.onIntent
import app.snapsync.query.RequestStatus
import app.snapsync.query.RequestStatuses
import app.snapsync.query.ScreenLookup
import app.snapsync.query.encryption.InviteRead
import app.snapsync.query.membership.JoinDetails
import app.snapsync.query.membership.MembershipShown
import app.snapsync.query.membership.MembershipStatuses
import app.snapsync.query.mobiledata.MobileDataQuery
import app.snapsync.query.mobiledata.MobileDataStatuses
import app.snapsync.query.network.NetworkStatusQuery
import app.snapsync.query.receiving.DownloadProgress
import app.snapsync.testids.testEventId
import app.snapsync.ui.components.LocalReduceMotion
import app.snapsync.ui.components.resources.wheel_end_hour
import app.snapsync.ui.components.resources.wheel_end_minute
import app.snapsync.ui.state.CreateStep
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
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
    config: Membership? = null,
    permission: PhotoGrant = PhotoGrant.FULL,
    refusal: VersionRefusal? = null,
    private val details: suspend (String) -> JoinDetails = { error("no details here") },
    network: NetworkAccess = NetworkAccess.Online(restricted = false),
    deviceRefusal: DeviceRefusal? = null,
    val clock: TestClock = TestClock(),
    val creation: MutableStateFlow<RequestStatus<CreateResult>> = MutableStateFlow(RequestStatus.Idle),
    val foreground: MutableStateFlow<ForegroundReturn> = MutableStateFlow(ForegroundReturn.NONE),
) {
    val config = MutableStateFlow(config)

    /** The invite the app was opened with, waiting for the screen until the screen tells it opened. */
    val invites = MutableStateFlow<InviteLinkVerdict?>(null)
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
    private val reports = RequestStatuses<ReportOutcome>()

    private val mobileDataStatuses = MobileDataStatuses()

    private fun <T> answered(id: RequestId, on: RequestStatuses<T>, answer: T?) {
        on.begun(id)
        answer?.let { on.done(id, it) }
    }

    /** A command the rig records as it is fired, answering it at once. */
    @OptIn(InboundImplementation::class)
    private fun <A> command(run: (A) -> Unit): UserCommand<A> = UserCommand(
        object : Background<A> {
            override fun handle(arg: A, completion: Completion) = run(arg)
        },
    )

    val host = StatusContainerHost(
        queries = ScreenQueries(
            sharing = MutableStateFlow(SharingProgressRead.NotYetRead),
            receiving = MutableStateFlow(DownloadProgress(0, 0)),
            grant = this.permission,
            membership = ShownMembership(this.config),
            // The invite as the query reads it for a plain event: the joined one's, none while none is joined.
            key = EventKeyView(
                presence = MutableStateFlow(KeyPresence.NotNeeded),
                invite = object : InviteRead {
                    override fun invite(): String? =
                        this@ContainerRig.config.value?.let { InviteLinkText.format(InviteLink(it.event.id, null)) }
                },
            ),
            invites = invites,
            creation = creation,
            versionRefusal = MutableStateFlow(refusal),
            verification = DeviceVerification(
                attested = MutableStateFlow(true),
                refusal = MutableStateFlow(deviceRefusal),
                fault = MutableStateFlow(null),
                service = MutableStateFlow(null),
            ),
            network = object : NetworkStatusQuery {
                override val access: StateFlow<NetworkAccess> = MutableStateFlow(network)
                override val returned: Flow<Unit> = emptyFlow()
            },
            foreground = foreground,
            inFront = MutableStateFlow(InFront.IN_FRONT),
            mobileData = object : MobileDataQuery {
                override fun current(): MobileDataChoice = MobileDataChoice.ALLOWED
                override val changes = mobileDataStatuses.change.status
            },
            requests = RequestStatusFlows(
                join = statuses.join.status,
                settings = statuses.settings.status,
                switch = statuses.switch.status,
                rename = statuses.rename.status,
                membershipRead = statuses.read.status,
                report = reports.status,
            ),
        ),
        scope = scope,
        cutoffFormatter = CutoffFormatter(now = { clock.instant }, zone = TimeZone.UTC),
        commands = UserCommands(
            inviteOpened = command { _: InviteLinkVerdict -> invites.value = null },
            leave = command { _: Unit ->
                record("leave")
                this@ContainerRig.config.value = null
            },
            create = command { r: CreateRequest ->
                record("create:${r.name}${r.openUntil?.let { ":open-until:${TimestampText.format(it.at)}" }.orEmpty()}")
                createdCell.update { it + Triple(r.name, r.startsAt, r.endsAt) }
                // Answered at once, as a core whose service failed would: the form returns with what was sent from it.
                creation.value = RequestStatus.Done(r.id, CreateResult.Failed(ServiceFault.UNREACHABLE))
            },
            join = command { r: JoinRequest ->
                record("commitJoin:${r.choice.event.id}:${r.choice.direction}:${r.choice.album}")
                answered(r.id, statuses.join, JoinCommit.Failed())
            },
            platform = PlatformCommands(
                share = command { r: ShareRequest -> record("share:${r.text}") },
                requestAccess = command { _: Unit -> record("requestAccess") },
                openSettings = command { _: Unit -> record("openSettings") },
                openLink = command { r: AppLinkRequest -> record("openLink:${r.opened()}") },
                choosePhotos = command { _: Unit -> record("choosePhotos") },
            ),
            reconfigure = command { r: SettingsRequest ->
                record("reconfigure:${r.choice.event}:${r.choice.direction}")
                answered(r.id, statuses.settings, SettingsResult.Saved)
            },
            rename = command { r: RenameRequest ->
                record("rename:${r.event}:${r.name}")
                answered(r.id, statuses.rename, null)
            },
            switch = command { r: SwitchRequest -> answered(r.id, statuses.switch, SwitchVerdict.Ask) },
            sendDiagnostics = command { r: ReportRequest ->
                // A report opened from "Report this" is marked: only it carries the refused verification's facts.
                record("sendDiagnostics:${r.note}" + if (r.context.verification) " [verification]" else "")
                answered(r.id, reports, ReportOutcome.SENT)
            },
            setMobileData = command { r: MobileDataRequest ->
                record("setMobileData:${r.choice == MobileDataChoice.ALLOWED}")
                answered(r.id, mobileDataStatuses.change, MobileDataOutcome.Saved(r.choice))
            },
            restoreKey = command { r: KeyRestoreRequest ->
                record("restoreKey")
                answered(r.id, statuses.restore, null)
            },
            retryMembershipRead = command { r: MembershipReadRequest ->
                record("retryMembershipRead")
                answered(r.id, statuses.read, Unit)
            },
        ),
        shareableCount = object : ScreenLookup<CaptureRange, Int?>("test", Dispatchers.Unconfined) {
            override suspend fun read(arg: CaptureRange): Int? = null
        },
        joinDetails = object : ScreenLookup<InviteLink, JoinDetails>("test", Dispatchers.Unconfined) {
            override suspend fun read(arg: InviteLink): JoinDetails = details(arg.event.value)
        },
        facts = BuildFacts(
            store = Store("App Store", RIG_STORE_URL),
            reportDestination = ReportDestination.DEVELOPER,
            label = BuildLabel.UNKNOWN,
            albumContents = AlbumContents.SharedAndReceived,
        ),
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

/** An active membership of the event [id] named [name], `[from, until]`, sharing all of it both ways. */
internal fun joinedTo(id: String, name: String, from: String, until: String): Membership {
    val start = EventStart(TimestampText.parse(from))
    val end = EventEnd(TimestampText.parse(until))
    val event = Event(
        id = testEventId(id),
        name = (EventName.of(name) as EventNameVerdict.Ok).name,
        range = (EventRange.of(start, end) as EventRangeVerdict.Ok).range,
        openUntil = null,
        deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
        encryption = Encryption.Plain,
        members = null,
    )
    val range = CaptureRange.of(CaptureCutoff(start.at), CaptureCeiling(end.at), start, end) as CaptureRangeVerdict.Ok
    return Membership.Active(event, Share(range.range, Direction.Both, AlbumChoice.LibraryOnly))
}

/** What [this] request opens, as the rig records it: the store page's url, or the site page's name. */
private fun AppLinkRequest.opened(): String = when (this) {
    is AppLinkRequest.StorePage -> store.url
    is AppLinkRequest.SitePage -> page.name
}

/** [config] as the screen reads it: held, or none — a rig's membership is never unreadable. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class ShownMembership(private val config: StateFlow<Membership?>) : StateFlow<MembershipShown> {
    override val value: MembershipShown get() = shown(config.value)
    override val replayCache: List<MembershipShown> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<MembershipShown>): Nothing {
        config.map(::shown).collect(collector)
        awaitCancellation()
    }

    private fun shown(held: Membership?): MembershipShown = held?.let { MembershipShown.Held(it) } ?: MembershipShown.None
}
