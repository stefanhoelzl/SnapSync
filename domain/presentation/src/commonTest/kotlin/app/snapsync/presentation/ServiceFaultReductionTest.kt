@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.presentation

import app.snapsync.domain.event.CreateResult
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.membership.JoinCommit
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.sharing.SharingProgress
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.query.RequestStatus
import app.snapsync.query.membership.JoinDetails
import app.snapsync.testids.testEventId
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.ServiceNotice
import app.snapsync.ui.state.SyncHealth
import app.snapsync.ui.state.step
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val EVENT_ID = "55555555-5555-4555-8555-555555555555"

/** An open membership whose event is under way at the fixed clock (2026-07-09T12:00Z). */
private val MEMBERSHIP = TestMembership(
    eventId = testEventId(EVENT_ID),
    name = "Lake Weekend",
    minPhotoDate = CaptureCutoff(timestamp("2026-07-06T00:00:00Z")),
    startsAt = EventStart(timestamp("2026-07-06T00:00:00Z")),
    endsAt = EventEnd(timestamp("2026-07-13T00:00:00Z")),
    maxPhotoDate = CaptureCeiling(timestamp("2026-07-13T00:00:00Z")),
    deletesAt = DeletesAt(timestamp("2026-08-05T00:00:00Z")),
)

private val DETAILS = EventDetails(
    MEMBERSHIP.name,
    MEMBERSHIP.startsAt,
    MEMBERSHIP.endsAt,
    MEMBERSHIP.deletesAt,
)

private val FOUND = found(MEMBERSHIP.name, MEMBERSHIP.startsAt, MEMBERSHIP.endsAt, MEMBERSHIP.deletesAt)

/** Everything of the member's shared, and photos still waiting. */
private val SETTLED = SharingProgressRead.Read(SharingProgress(shared = 4, total = 4, handedOver = 0))
private val WAITING = SharingProgressRead.Read(SharingProgress(shared = 2, total = 4, handedOver = 2))

/** What the host reads of this phone's verification and of the service — what [ServiceFaultReductionTest]'s cases vary. */
private data class Verified(
    val attested: Boolean = true,
    val refusal: DeviceRefusal? = null,
    val attestFault: ServiceFault? = null,
    val service: ServiceFault? = null,
)

/**
 * How a failing service reaches what the member is told (decision record `changes/tell-service-faults`): the status
 * line's rungs, the create message, the join screen's failures — and the join screen's own reload once an update has
 * passed, on virtual time. The rename dialog's words are `JoinedLayerTest`'s.
 */
class ServiceFaultReductionTest {

    private fun host(
        scope: CoroutineScope,
        trust: Verified = Verified(),
        config: TestMembership? = MEMBERSHIP,
        snapshot: SharingProgressRead = WAITING,
        creation: RequestStatus<CreateResult> = Creating.Idle,
        load: suspend (String) -> JoinDetails = { FOUND },
        commit: suspend () -> JoinCommit = { JoinCommit.Committed },
    ) = TestCommands(commitJoin = { commit() }).let { commands ->
        testHost(
            testQueries(
                FixedSync(snapshot),
                MutableStateFlow(PhotoGrant.FULL),
                MutableStateFlow(config).held(),
                creation = MutableStateFlow(creation),
                verification = testVerification(
                    MutableStateFlow(trust.attested),
                    MutableStateFlow(trust.refusal),
                    MutableStateFlow(trust.attestFault),
                    MutableStateFlow(trust.service),
                ),
                requests = commands.requests.flows,
                network = AlwaysOnline,
                mobileData = AlwaysAllowed,
            ),
            scope,
            cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-09T12:00:00Z") }, zone = TimeZone.UTC),
            commands = commands.door,
            shareableCount = noQueries,
            joinDetails = joinDetails(load),
            reduction = Dispatchers.Default,
        )
    }

    /** The join screen opened on an invite to [EVENT_ID], its details loaded and ready to confirm. */
    private suspend fun StatusContainerHost.opened() {
        onInvite(invited(testEventId(EVENT_ID), null))
        awaitJoin { it.phase.step == JoinPhase.Detailed.Step.Ready }
    }

    private fun StatusContainerHost.health(): SyncHealth = assertIs<Layer.Joined>(screen.value.layer).health

    private suspend fun StatusContainerHost.awaitJoin(that: (Layer.JoiningEvent) -> Boolean): Layer.JoiningEvent =
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                screen.first { (it.layer as? Layer.JoiningEvent)?.let(that) == true }.layer as Layer.JoiningEvent
            }
        }

    // ── the words ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun each_failure_has_its_own_notice_and_message() {
        assertEquals(
            listOf(ServiceNotice.UNREACHABLE, ServiceNotice.TROUBLE, ServiceNotice.UPDATING),
            ServiceFault.entries.map { it.notice },
        )
        assertEquals(
            listOf(ScreenMessage.SERVICE_UNREACHABLE, ScreenMessage.SERVICE_TROUBLE, ScreenMessage.SERVICE_UPDATING),
            ServiceFault.entries.map { it.message },
        )
    }

    @Test
    fun an_update_s_wait_is_what_the_service_suggested_bounded() {
        assertEquals(60.seconds, maintenanceWait(60))
        assertEquals(30.seconds, maintenanceWait(null), "no hint: a default")
        assertEquals(5.seconds, maintenanceWait(0), "never a spin")
        assertEquals(300.seconds, maintenanceWait(86_400), "never a stall")
    }

    // ── the status line ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun photos_waiting_while_the_service_fails_are_told_which_way() = runTest {
        assertEquals(
            SyncHealth.ServiceFailing(ServiceNotice.TROUBLE),
            host(backgroundScope, Verified(service = ServiceFault.TROUBLE)).health(),
        )
    }

    @Test
    fun nothing_waiting_is_up_to_date_however_the_service_fails() = runTest {
        assertEquals(
            SyncHealth.InSync,
            host(backgroundScope, Verified(service = ServiceFault.TROUBLE), snapshot = SETTLED).health(),
        )
    }

    @Test
    fun a_proof_the_service_failed_to_renew_is_the_service_s_condition_never_cannot_verify() = runTest {
        assertEquals(
            SyncHealth.ServiceFailing(ServiceNotice.UPDATING),
            host(backgroundScope, Verified(attested = false, attestFault = ServiceFault.UPDATING)).health(),
        )
        assertEquals(
            SyncHealth.Unattested(),
            host(backgroundScope, Verified(attested = false)).health(),
            "answered, no proof",
        )
        assertIs<SyncHealth.Unattested>(
            host(
                backgroundScope,
                Verified(
                    attested = false,
                    refusal = DeviceRefusal.DEVICE_MODIFIED,
                    attestFault = ServiceFault.UNREACHABLE,
                ),
            ).health(),
            "a refusal is never masked",
        )
    }

    // ── the create message ───────────────────────────────────────────────────────────────

    @Test
    fun a_create_the_service_failed_says_which_way() = runTest {
        fun error(creation: RequestStatus<CreateResult>, attestFault: ServiceFault? = null) =
            assertIs<Layer.CreateEvent>(
                host(
                    backgroundScope,
                    Verified(attestFault = attestFault),
                    config = null,
                    creation = creation,
                ).screen.value.layer,
            ).error
        assertEquals(
            ScreenMessage.SERVICE_TROUBLE,
            error(Creating.answered(CreateResult.Failed(ServiceFault.TROUBLE))),
        )
        assertEquals(
            ScreenMessage.SERVICE_UNREACHABLE,
            error(Creating.answered(CreateResult.Unverified), attestFault = ServiceFault.UNREACHABLE),
            "refused for want of a proof the service could not be reached for",
        )
        assertEquals(ScreenMessage.CREATE_FAILED, error(Creating.answered(CreateResult.Unverified)))
    }

    // ── the join screen ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_switch_whose_invite_the_service_failed_to_load_says_which_way() = runTest {
        val joined = host(backgroundScope, load = { JoinDetails.Unanswered(ServiceFault.TROUBLE) })
        joined.onInvite(invited(testEventId("66666666-6666-4666-8666-666666666666"), null))
        val shown = withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                joined.screen.first { (it.layer as? Layer.Joined)?.pendingSwitch?.phase == JoinPhase.LoadFailed }
            }
        }
        assertEquals(ServiceNotice.TROUBLE, (shown.layer as Layer.Joined).pendingSwitch?.service)
    }

    @Test
    fun a_join_the_service_failed_says_which_way() = runTest {
        val failed = host(backgroundScope, config = null, commit = { JoinCommit.Failed(ServiceFault.TROUBLE) })
        failed.opened()
        failed.onConfirmJoin()
        failed.awaitJoin { it.phase.step == JoinPhase.Detailed.Step.CommitFailed && it.service == ServiceNotice.TROUBLE }

        val unproved = host(
            backgroundScope,
            Verified(attestFault = ServiceFault.UPDATING),
            config = null,
            commit = { JoinCommit.Unverified },
        )
        unproved.opened()
        unproved.onConfirmJoin()
        unproved.awaitJoin { it.phase.step == JoinPhase.Detailed.Step.CommitFailed && it.service == ServiceNotice.UPDATING }
    }

    // ── an update's reload, on a scheduler of the test's own ─────────────────────────────────────────

    /**
     * The container's scope on a scheduler of its own — not `runTest`'s, which skips time whenever the test waits — so
     * the service's suggested wait passes exactly when [advanceBy] says (as `NoticeTimersTest` does).
     */
    private class Timers {
        private val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

        fun advanceBy(seconds: Int) {
            scheduler.advanceTimeBy(seconds * 1_000L)
            scheduler.runCurrent()
        }
    }

    private fun onTimers(body: suspend (Timers) -> Unit) = runTest {
        val timers = Timers()
        try {
            body(timers)
        } finally {
            timers.scope.cancel()
        }
    }

    /** A join screen opened on an invite, its details loaded by the answers [answers] give in turn. */
    private suspend fun opened(timers: Timers, loads: MutableList<Int>, answers: (Int) -> JoinDetails) = host(
        timers.scope,
        config = null,
        load = { answers(loads.size).also { loads += loads.size } },
    ).also {
        it.onInvite(invited(testEventId(EVENT_ID), null))
        timers.advanceBy(0)
    }

    private suspend fun Timers.retried(host: StatusContainerHost) {
        host.onRetryLoad().join()
        advanceBy(0)
    }

    private val updating = JoinDetails.Unanswered(ServiceFault.UPDATING, retryAfterSeconds = 60)

    @Test
    fun an_invite_opened_during_an_update_loads_again_once_the_service_s_wait_has_passed() = onTimers { timers ->
        val loads = mutableListOf<Int>()
        val host = host(
            timers.scope,
            config = null,
            load = { (if (loads.isEmpty()) updating else FOUND).also { loads += loads.size } },
        )
        host.onInvite(invited(testEventId(EVENT_ID), null)).join()
        timers.advanceBy(0)
        host.awaitJoin { it.phase == JoinPhase.LoadFailed && it.service == ServiceNotice.UPDATING }

        timers.advanceBy(60)
        host.awaitJoin { it.phase is JoinPhase.Detailed }
        assertEquals(2, loads.size)
    }

    @Test
    fun a_retry_during_an_update_waits_afresh_and_loads_once() = onTimers { timers ->
        val loads = mutableListOf<Int>()
        val host = opened(timers, loads) { if (it < 2) updating else FOUND }
        timers.advanceBy(30)
        timers.retried(host)
        assertEquals(2, loads.size, "the member's retry")

        timers.advanceBy(59)
        assertEquals(2, loads.size, "the first wait was replaced by the retry's own")
        timers.advanceBy(1)
        host.awaitJoin { it.phase is JoinPhase.Detailed }
        assertEquals(3, loads.size, "one reload — never two")
    }

    @Test
    fun a_join_screen_left_during_an_update_loads_nothing_more() = onTimers { timers ->
        val loads = mutableListOf<Int>()
        val host = opened(timers, loads) { updating }
        host.onCancelJoin().join()
        timers.advanceBy(120)
        assertEquals(1, loads.size)
    }
}
