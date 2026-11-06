package app.snapsync.presentation

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.Event
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.EventKeyId
import app.snapsync.domain.event.EventName
import app.snapsync.domain.event.EventNameVerdict
import app.snapsync.domain.event.EventRange
import app.snapsync.domain.event.EventRangeVerdict
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.membership.InviteFact
import app.snapsync.domain.membership.JoinChoice
import app.snapsync.domain.membership.JoinCommit
import app.snapsync.domain.membership.RenameResult
import app.snapsync.domain.membership.RestoreAnswer
import app.snapsync.domain.membership.SettingsResult
import app.snapsync.domain.membership.ShareChoice
import app.snapsync.domain.membership.SwitchVerdict
import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.domain.mobiledata.MobileDataOutcome
import app.snapsync.domain.network.NetworkAccess
import app.snapsync.domain.report.ReportContext
import app.snapsync.domain.report.ReportOutcome
import app.snapsync.domain.request.AppLinkRequest
import app.snapsync.domain.request.CreateRequest
import app.snapsync.domain.request.JoinRequest
import app.snapsync.domain.request.KeyRestoreRequest
import app.snapsync.domain.request.MobileDataRequest
import app.snapsync.domain.request.RenameRequest
import app.snapsync.domain.request.ReportRequest
import app.snapsync.domain.request.RequestId
import app.snapsync.domain.request.SettingsRequest
import app.snapsync.domain.request.ShareRequest
import app.snapsync.domain.request.SwitchRequest
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.inbound.Background
import app.snapsync.inbound.Completion
import app.snapsync.inbound.InboundImplementation
import app.snapsync.model.SyncStatus
import app.snapsync.query.RequestStatus
import app.snapsync.query.RequestStatuses
import app.snapsync.query.ScreenLookup
import app.snapsync.query.membership.JoinDetails
import app.snapsync.query.membership.MembershipStatuses
import app.snapsync.query.mobiledata.MobileDataQuery
import app.snapsync.query.mobiledata.MobileDataStatuses
import app.snapsync.query.network.NetworkStatusQuery
import app.snapsync.testids.testEventId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** A lookup asked on the asking thread, answering [answer]. */
internal fun <A, R> lookup(answer: suspend (A) -> R): ScreenLookup<A, R> =
    object : ScreenLookup<A, R>("test", Dispatchers.Unconfined) {
        override suspend fun read(arg: A): R = answer(arg)
    }

/** A shareable count of [count]; by default no count is available. */
internal fun counting(
    count: suspend (CaptureRange) -> Int? = { null },
): ScreenLookup<CaptureRange, Int?> = lookup(count)

/** A shareable count that answers nothing: no count is available. */
internal val noQueries: ScreenLookup<CaptureRange, Int?> = counting()

/** The join screen's read, answering [load] for the invite's event id — an event found is the invite's own. */
internal fun joinDetails(load: suspend (String) -> JoinDetails): ScreenLookup<InviteLink, JoinDetails> =
    lookup { invite ->
        when (val details = load(invite.event.value)) {
            is JoinDetails.Found -> details.copy(event = details.event.copy(id = invite.event))
            else -> details
        }
    }

/** A join screen's read that never finds an answer. */
internal val unanswered: ScreenLookup<InviteLink, JoinDetails> =
    joinDetails { JoinDetails.Unanswered(ServiceFault.UNREACHABLE) }

/** The event the server serves for an invite: [name] over `[startsAt, endsAt]`, found and not deleted unless [completed]. */
internal fun found(
    name: String,
    startsAt: EventStart,
    endsAt: EventEnd,
    deletesAt: DeletesAt,
    completed: Boolean = false,
    keyId: String? = null,
    openUntil: OpenUntil? = null,
): JoinDetails = JoinDetails.Found(servedEvent(name, startsAt, endsAt, deletesAt, keyId, openUntil), completed)

internal fun servedEvent(
    name: String,
    startsAt: EventStart,
    endsAt: EventEnd,
    deletesAt: DeletesAt,
    keyId: String? = null,
    openUntil: OpenUntil? = null,
): Event = Event(
    id = testEventId("served"),
    name = (EventName.of(name) as EventNameVerdict.Ok).name,
    range = (EventRange.of(startsAt, endsAt) as EventRangeVerdict.Ok).range,
    openUntil = openUntil,
    deletesAt = deletesAt,
    encryption = keyId?.let { Encryption.Encrypted(EventKeyId(it)) } ?: Encryption.Plain,
    members = null,
)

/**
 * The membership's request statuses a test's commands answer on — what the live door's work writes. A request's
 * answer runs where it is asked ([Dispatchers.Unconfined]), so one that does not suspend is answered before its id is
 * returned; one that suspends is answered when it resumes. An answer of `null` leaves the request running, and one that
 * throws is answered [ask]'s `unanswered` — what the core's work answers when it ends early.
 */
internal class TestRequests {
    val statuses = MembershipStatuses()

    /** A bug report's answers. */
    val reports = RequestStatuses<ReportOutcome>()
    val flows = RequestStatusFlows(
        join = statuses.join.status,
        settings = statuses.settings.status,
        switch = statuses.switch.status,
        rename = statuses.rename.status,
        report = reports.status,
    )

    /** The mobile-data choice's changes, answered as the live door's work answers them. */
    val mobileData = MobileDataStatuses()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** [id], answered on [on] by [answer] — [unanswered] if it throws, and left running if it answers `null`. */
    fun <T : Any> answer(id: RequestId, on: RequestStatuses<T>, unanswered: T, answer: suspend () -> T?) {
        on.begun(id)
        scope.launch {
            val answered = try {
                answer()
            } catch (_: IllegalStateException) {
                // What a test's answer throws, it throws to say the work ended early: answered as the core answers it.
                unanswered
            }
            answered?.let { on.done(id, it) }
        }
    }
}

/**
 * The screen's door with the inert defaults the production one does not carry: each command a [Background] fake that
 * runs its lambda where it is fired, answering a request on [requests] as the live door's work answers it.
 */
internal class TestCommands(
    private val leave: () -> Unit = {},
    private val create: (name: String, startsAt: EventStart, endsAt: EventEnd, openUntil: OpenUntil?) -> Unit =
        { _, _, _, _ -> },
    private val commitJoin: suspend (JoinChoice) -> JoinCommit? = { _ -> JoinCommit.Failed() },
    private val share: (url: String, title: String) -> Unit = { _, _ -> },
    private val requestAccess: () -> Unit = {},
    private val openSettings: () -> Unit = {},
    private val openLink: (url: String) -> Unit = {},
    private val reconfigure: suspend (ShareChoice) -> SettingsResult? = { SettingsResult.Saved },
    private val rename: suspend (eventId: EventId, name: String) -> RenameResult? = { _, _ -> null },
    private val switch: suspend (InviteFact) -> SwitchVerdict? = { SwitchVerdict.Ask },
    private val sendDiagnostics: suspend (note: String, context: ReportContext) -> ReportOutcome =
        { _, _ -> ReportOutcome.SENT },
) {
    /** The menu's mobile-data switch saves; a test about it answers otherwise. */
    var setMobileData: suspend (MobileDataChoice) -> MobileDataOutcome? = { MobileDataOutcome.Saved(it) }

    /** Restoring a lost key is answered nowhere the screen reads; a test about it answers otherwise. */
    var restoreKey: suspend (EventKey) -> RestoreAnswer? = { RestoreAnswer.NothingChanged }

    /** What the door does as each request is handed to it, before its work runs — a throw there is the firing intent's. */
    var handedOver: (Any) -> Unit = {}

    /** Where this door's requests are answered — the statuses a host built over it reads. */
    val requests = TestRequests()

    /** The door a host is built over. */
    val door: UserCommands = UserCommands(
        leave = command { _: Unit -> leave() },
        create = command { r: CreateRequest -> create(r.name, r.startsAt, r.endsAt, r.openUntil) },
        join = command { r: JoinRequest ->
            requests.answer(r.id, requests.statuses.join, JoinCommit.Failed()) { commitJoin(r.choice) }
        },
        share = command { r: ShareRequest -> share(r.text, r.title) },
        requestAccess = command { _: Unit -> requestAccess() },
        openSettings = command { _: Unit -> openSettings() },
        openLink = command { r: AppLinkRequest -> openLink(r.url) },
        // No presentation test is about the picker; its binding is the shells' and compose/'s.
        choosePhotos = command { _: Unit -> },
        reconfigure = command { r: SettingsRequest ->
            requests.answer(r.id, requests.statuses.settings, SettingsResult.NotCurrent) { reconfigure(r.choice) }
        },
        rename = command { r: RenameRequest ->
            requests.answer(r.id, requests.statuses.rename, RenameResult.Failed()) { rename(r.event, r.name) }
        },
        switch = command { r: SwitchRequest ->
            requests.answer(r.id, requests.statuses.switch, SwitchVerdict.Ask) { switch(r.invite) }
        },
        sendDiagnostics = command { r: ReportRequest ->
            requests.answer(r.id, requests.reports, ReportOutcome.NOT_SENT) { sendDiagnostics(r.note, r.context) }
        },
        setMobileData = command { r: MobileDataRequest ->
            requests.answer(r.id, requests.mobileData.change, MobileDataOutcome.NotSaved(r.choice)) {
                setMobileData(r.choice)
            }
        },
        restoreKey = command { r: KeyRestoreRequest ->
            requests.answer(r.id, requests.statuses.restore, RestoreAnswer.Failed) { restoreKey(r.key) }
        },
    )

    @OptIn(InboundImplementation::class)
    private fun <A> command(run: (A) -> Unit): UserCommand<A> = UserCommand(
        object : Background<A> {
            override fun handle(arg: A, completion: Completion) {
                handedOver(arg as Any)
                run(arg)
            }
        },
    )
}

/** Diagnostics that go nowhere unless a test is about them. */
internal fun testDiagnostics(log: (String) -> Unit = {}, onIntentError: (Throwable) -> Unit = {}) =
    StatusDiagnostics(log = log, onIntentError = onIntentError)

/** A sync snapshot that holds [status] and never moves — for a test about something other than the snapshot. */
internal class FixedSync(status: SyncStatus) : SyncStatusSource {
    override val status: StateFlow<SyncStatus> = MutableStateFlow(status)
}

/** A host whose photos may always use mobile data, and whose choice no test changes. */
internal object AlwaysAllowed : MobileDataQuery {
    override fun current(): MobileDataChoice = MobileDataChoice.ALLOWED
    override val changes: StateFlow<RequestStatus<MobileDataOutcome>> = MutableStateFlow(RequestStatus.Idle)
}

/** A host with no network watch: online, and never returning from anything. */
internal object AlwaysOnline : NetworkStatusQuery {
    override val access: StateFlow<NetworkAccess> = MutableStateFlow(NetworkAccess.Online(restricted = false))
    override val returned: Flow<Unit> = emptyFlow()
}
