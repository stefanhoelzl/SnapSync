package app.snapsync.presentation

import app.snapsync.domain.album.AlbumContents
import app.snapsync.domain.event.CreateResult
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.foreground.ForegroundReturn
import app.snapsync.domain.foreground.InFront
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.domain.invite.JoinedInvite
import app.snapsync.domain.report.BuildLabel
import app.snapsync.domain.report.ReportDestination
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.domain.version.Store
import app.snapsync.domain.version.VersionRefusal
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Log
import app.snapsync.inbound.LogLevel
import app.snapsync.inbound.LogLine
import app.snapsync.inbound.LogWriter
import app.snapsync.presentation.Switches.mobileData
import app.snapsync.query.RequestStatus
import app.snapsync.query.ScreenLookup
import app.snapsync.query.encryption.InviteRead
import app.snapsync.query.membership.JoinDetails
import app.snapsync.query.membership.MembershipShown
import app.snapsync.query.mobiledata.MobileDataQuery
import app.snapsync.query.network.NetworkStatusQuery
import app.snapsync.query.receiving.DownloadProgress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** The screen's queries as a test states them: each source a resting value unless the test is about it. */
@Suppress("LongParameterList")
internal fun testQueries(
    sync: StateFlow<SharingProgressRead>,
    permission: StateFlow<PhotoGrant>,
    membership: StateFlow<MembershipShown>,
    creation: StateFlow<RequestStatus<CreateResult>> = MutableStateFlow(RequestStatus.Idle),
    invites: StateFlow<InviteLinkVerdict?> = MutableStateFlow(null),
    requests: RequestStatusFlows = TestCommands().requests.flows,
    // A READ (0, 0), not `DownloadProgress.UNREAD`: "this host has no download arm" is an answer, while unread holds
    // the health at loading.
    download: StateFlow<DownloadProgress> = MutableStateFlow(DownloadProgress(0, 0)),
    verification: DeviceVerification = testVerification(),
    versionRefusal: StateFlow<VersionRefusal?> = MutableStateFlow(null),
    network: NetworkStatusQuery = AlwaysOnline,
    mobileData: MobileDataQuery = AlwaysAllowed,
    foreground: StateFlow<ForegroundReturn> = MutableStateFlow(ForegroundReturn.NONE),
    inFront: StateFlow<InFront> = MutableStateFlow(InFront.IN_FRONT),
    eventKey: EventKeyView = testKeys(),
): ScreenQueries = ScreenQueries(
    sharing = sync,
    receiving = download,
    grant = permission,
    membership = membership,
    key = eventKey,
    invites = invites,
    creation = creation,
    requests = requests,
    verification = verification,
    versionRefusal = versionRefusal,
    network = network,
    mobileData = mobileData,
    foreground = foreground,
    inFront = inFront,
)

/** The phone's standing as a test states it: verified, and the service answering, unless the test is about it. */
internal fun testVerification(
    attested: StateFlow<Boolean> = MutableStateFlow(true),
    refusal: StateFlow<DeviceRefusal?> = MutableStateFlow(null),
    fault: StateFlow<ServiceFault?> = MutableStateFlow(null),
    service: StateFlow<ServiceFault?> = MutableStateFlow(null),
): DeviceVerification = DeviceVerification(attested, refusal, fault, service)

/** The joined event's key as a test states it: a plain event's — none needed, and no invite — unless it is about it. */
internal fun testKeys(
    presence: StateFlow<KeyPresence> = MutableStateFlow(KeyPresence.NotNeeded),
    invite: InviteRead = NoInvite,
): EventKeyView = EventKeyView(presence, invite)

/** No invite to share. */
private object NoInvite : InviteRead {
    override fun invite(): JoinedInvite? = null
}

/** The build's constants as a test states them: the distributed build's, unless the test is about them. */
internal fun testFacts(
    store: Store? = null,
    reportDestination: ReportDestination = ReportDestination.DEVELOPER,
    build: BuildLabel = BuildLabel.UNKNOWN,
    albumContents: AlbumContents = AlbumContents.SharedAndReceived,
): BuildFacts = BuildFacts(store, reportDestination, build, albumContents)

/** The screen over [queries], everything else a resting value unless the test is about it. */
@Suppress("LongParameterList")
internal fun testHost(
    queries: ScreenQueries,
    scope: CoroutineScope,
    reduction: CoroutineDispatcher,
    cutoffFormatter: CutoffFormatter,
    commands: UserCommands = TestCommands().door,
    shareableCount: ScreenLookup<CaptureRange, Int?> = noQueries,
    joinDetails: ScreenLookup<InviteLink, JoinDetails> = unanswered,
    facts: BuildFacts = testFacts(),
): StatusContainerHost =
    StatusContainerHost(queries, shareableCount, joinDetails, commands, facts, cutoffFormatter, scope, reduction)

/**
 * Every line the structured log writes from its installing on, captured in order — how a test reads what the screen
 * logged. The log's writers are the process's, so the last one installed is the one that captures.
 */
@OptIn(CompositionOnly::class)
internal class TestLog : LogWriter {
    private val written = MutableStateFlow<List<LogLine>>(emptyList())

    init {
        Log.install(listOf(this))
    }

    override fun write(line: LogLine) {
        written.update { it + line }
    }

    /** The lines written so far. */
    val lines: List<LogLine> get() = written.value

    /** The lines written so far at `Error`: what an intent that failed outright leaves. */
    val errors: List<LogLine> get() = lines.filter { it.level == LogLevel.ERROR }

    /** The first line written at `Error`, once one is. */
    suspend fun awaitError(): LogLine = written.map { lines -> lines.firstOrNull { it.level == LogLevel.ERROR } }
        .filterNotNull()
        .first()
}
