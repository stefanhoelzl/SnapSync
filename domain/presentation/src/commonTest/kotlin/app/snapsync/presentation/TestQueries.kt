package app.snapsync.presentation

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.Event
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
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
import app.snapsync.domain.membership.SettingsResult
import app.snapsync.domain.membership.ShareChoice
import app.snapsync.domain.membership.SwitchVerdict
import app.snapsync.domain.request.RequestId
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.model.ReportContext
import app.snapsync.model.ReportOutcome
import app.snapsync.model.SyncStatus
import app.snapsync.model.UserCommands
import app.snapsync.model.UserQueries
import app.snapsync.query.RequestStatuses
import app.snapsync.query.membership.JoinDetails
import app.snapsync.query.membership.JoinDetailsQuery
import app.snapsync.query.membership.MembershipStatuses
import app.snapsync.testids.testEventId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A query bundle whose shareable count is [count]; by default no count is available. */
internal fun counting(count: suspend (CaptureCutoff, CaptureCeiling?) -> Int? = { _, _ -> null }): UserQueries =
    object : UserQueries {
        override suspend fun shareableCount(cutoff: CaptureCutoff, until: CaptureCeiling?) = count(cutoff, until)
    }

/** A query bundle that answers nothing: no count is available. */
internal val noQueries: UserQueries = counting()

/** The join screen's read, answering [load] for the invite's event id — an event found is the invite's own. */
internal fun joinDetails(load: suspend (String) -> JoinDetails): JoinDetailsQuery = object : JoinDetailsQuery {
    override suspend fun load(invite: InviteLink): JoinDetails = when (val details = load(invite.event.value)) {
        is JoinDetails.Found -> details.copy(event = details.event.copy(id = invite.event))
        else -> details
    }
}

/** A join screen's read that never finds an answer. */
internal val unanswered: JoinDetailsQuery = joinDetails { JoinDetails.Unanswered(ServiceFault.UNREACHABLE) }

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
    keyId = keyId?.let(::EventKeyId),
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
    val flows = RequestStatusFlows(
        join = statuses.join.status,
        settings = statuses.settings.status,
        switch = statuses.switch.status,
        rename = statuses.rename.status,
    )
    private var next = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    fun <T : Any> ask(on: RequestStatuses<T>, unanswered: T, answer: suspend () -> T?): RequestId {
        val id = RequestId(++next)
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
        return id
    }
}

/** The user-command bundle with the inert defaults the production implementation does not carry. */
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
) : UserCommands {
    /** Where this bundle's requests are answered — the statuses a host built over it reads. */
    val requests = TestRequests()

    override fun leave() = leave.invoke()

    override suspend fun create(name: String, startsAt: EventStart, endsAt: EventEnd, openUntil: OpenUntil?) =
        create.invoke(name, startsAt, endsAt, openUntil)

    override fun commitJoin(choice: JoinChoice) = requests.ask(
        requests.statuses.join,
        JoinCommit.Failed(),
    ) { commitJoin.invoke(choice) }

    override suspend fun share(url: String, title: String) = share.invoke(url, title)

    override suspend fun requestAccess() = requestAccess.invoke()

    override suspend fun openSettings() = openSettings.invoke()

    override suspend fun openLink(url: String) = openLink.invoke(url)

    // No presentation test is about the picker; its binding is the shells' and compose/'s.
    override suspend fun choosePhotos() = Unit

    override fun reconfigure(choice: ShareChoice) = requests.ask(
        requests.statuses.settings,
        SettingsResult.NotCurrent,
    ) { reconfigure.invoke(choice) }

    override fun rename(eventId: EventId, name: String) =
        requests.ask(requests.statuses.rename, RenameResult.Failed()) { rename.invoke(eventId, name) }

    override fun switch(invite: InviteFact) = requests.ask(
        requests.statuses.switch,
        SwitchVerdict.Ask,
    ) { switch.invoke(invite) }

    override suspend fun sendDiagnostics(note: String, context: ReportContext) = sendDiagnostics.invoke(note, context)

    // The menu's mobile-data switch saves; a test about it overrides this.
    override suspend fun setMobileData(on: Boolean) = true

    override suspend fun restoreEventKey(linkKey: String) = false
}

/** Diagnostics that go nowhere unless a test is about them. */
internal fun testDiagnostics(log: (String) -> Unit = {}, onIntentError: (Throwable) -> Unit = {}) =
    StatusDiagnostics(log = log, onIntentError = onIntentError)

/** A sync snapshot that holds [status] and never moves — for a test about something other than the snapshot. */
internal class FixedSync(status: SyncStatus) : SyncStatusSource {
    override val status: StateFlow<SyncStatus> = MutableStateFlow(status)
}
