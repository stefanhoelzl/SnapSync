@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.presentation

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.membership.JoinCommit
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.feature.creation.readmodel.CreationFailureReason
import app.snapsync.feature.creation.readmodel.CreationStatus
import app.snapsync.format.InviteLinkText
import app.snapsync.format.TimestampText
import app.snapsync.model.EventConfig
import app.snapsync.model.GalleryAccess
import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus
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
private val MEMBERSHIP = EventConfig(
    eventId = testEventId(EVENT_ID),
    name = "Lake Weekend",
    minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-06T00:00:00Z")),
    startsAt = EventStart(TimestampText.parse("2026-07-06T00:00:00Z")),
    endsAt = EventEnd(TimestampText.parse("2026-07-13T00:00:00Z")),
    maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-13T00:00:00Z")),
    deletesAt = DeletesAt(TimestampText.parse("2026-08-05T00:00:00Z")),
)

private val DETAILS = EventDetails(
    MEMBERSHIP.name,
    MEMBERSHIP.startsAt,
    MEMBERSHIP.endsAt,
    MEMBERSHIP.deletesAt,
)

private val FOUND = found(MEMBERSHIP.name, MEMBERSHIP.startsAt, MEMBERSHIP.endsAt, MEMBERSHIP.deletesAt)

/** Everything of the member's shared, and photos still waiting. */
private val SETTLED = SyncStatus.Ready(SyncProgress(0, 4, 4, 0, active = false, estimatedRemaining = null))
private val WAITING = SyncStatus.Ready(SyncProgress(2, 2, 4, 0, active = false, estimatedRemaining = null))

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
        config: EventConfig? = MEMBERSHIP,
        snapshot: SyncStatus = WAITING,
        creation: CreationStatus = CreationStatus.Idle,
        pending: PendingJoin? = null,
        load: suspend (String) -> JoinDetails = { FOUND },
        commit: suspend () -> JoinCommit = { JoinCommit.Committed },
    ) = TestCommands(commitJoin = { commit() }).let { commands ->
        StatusContainerHost(
            StatusSources(
                FixedSync(snapshot),
                MutableStateFlow(GalleryAccess.GRANTED),
                MutableStateFlow(config),
                creation = MutableStateFlow(creation),
                verification = DeviceVerification(
                    MutableStateFlow(trust.attested),
                    MutableStateFlow(trust.refusal),
                    MutableStateFlow(trust.attestFault),
                    MutableStateFlow(trust.service),
                ),
                pending = MutableStateFlow(pending),
                requests = commands.requests.flows,
            ),
            scope,
            cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-09T12:00:00Z") }, zone = TimeZone.UTC),
            commands = commands,
            queries = noQueries,
            joinDetails = joinDetails(load),
            diagnostics = testDiagnostics(),
            reduction = Dispatchers.Default,
        )
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
        fun error(creation: CreationStatus, attestFault: ServiceFault? = null) =
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
            error(CreationStatus.Failed(CreationFailureReason.SERVER, ServiceFault.TROUBLE)),
        )
        assertEquals(
            ScreenMessage.SERVICE_UNREACHABLE,
            error(CreationStatus.Failed(CreationFailureReason.UNVERIFIED), attestFault = ServiceFault.UNREACHABLE),
            "refused for want of a proof the service could not be reached for",
        )
        assertEquals(ScreenMessage.CREATE_FAILED, error(CreationStatus.Failed(CreationFailureReason.UNVERIFIED)))
    }

    // ── the join screen ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_join_the_service_failed_says_which_way() = runTest {
        val ready = PendingJoin(
            testEventId(EVENT_ID),
            JoinProgress.Detailed(DETAILS, JoinPhase.Detailed.Step.Ready),
            loaded = (FOUND as JoinDetails.Found).event.copy(id = testEventId(EVENT_ID)),
        )
        val failed = host(
            backgroundScope,
            config = null,
            pending = ready,
            commit = { JoinCommit.Failed(ServiceFault.TROUBLE) },
        )
        failed.onConfirmJoin()
        failed.awaitJoin { it.phase.step == JoinPhase.Detailed.Step.CommitFailed && it.service == ServiceNotice.TROUBLE }

        val unproved = host(
            backgroundScope,
            Verified(attestFault = ServiceFault.UPDATING),
            config = null,
            pending = ready,
            commit = { JoinCommit.Unverified },
        )
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

    /** A join screen whose details load failed, and the loads [answers] give in turn. */
    private fun failedLoad(timers: Timers, loads: MutableList<Int>, answers: (Int) -> JoinDetails) = host(
        timers.scope,
        config = null,
        pending = PendingJoin(testEventId(EVENT_ID), JoinProgress.LoadFailed),
        load = { answers(loads.size).also { loads += loads.size } },
    )

    private suspend fun Timers.retried(host: StatusContainerHost) {
        host.onRetryLoad().join()
        advanceBy(0)
    }

    private val updating = JoinDetails.Unanswered(ServiceFault.UPDATING, retryAfterSeconds = 60)

    @Test
    fun a_load_during_an_update_loads_again_once_the_service_s_wait_has_passed() = onTimers { timers ->
        val loads = mutableListOf<Int>()
        val host = failedLoad(timers, loads) { if (it == 0) updating else FOUND }
        timers.retried(host)
        host.awaitJoin { it.phase == JoinPhase.LoadFailed && it.service == ServiceNotice.UPDATING }

        timers.advanceBy(59)
        assertEquals(1, loads.size, "not before the service's suggested wait")
        timers.advanceBy(1)
        host.awaitJoin { it.phase is JoinPhase.Detailed }
        assertEquals(2, loads.size)
    }

    @Test
    fun an_invite_opened_during_an_update_loads_again_once_the_service_s_wait_has_passed() = onTimers { timers ->
        val loads = mutableListOf<Int>()
        val host = host(
            timers.scope,
            config = null,
            load = { (if (loads.isEmpty()) updating else FOUND).also { loads += loads.size } },
        )
        host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null))).join()
        timers.advanceBy(0)
        host.awaitJoin { it.phase == JoinPhase.LoadFailed && it.service == ServiceNotice.UPDATING }

        timers.advanceBy(60)
        host.awaitJoin { it.phase is JoinPhase.Detailed }
        assertEquals(2, loads.size)
    }

    @Test
    fun a_retry_during_an_update_waits_afresh_and_loads_once() = onTimers { timers ->
        val loads = mutableListOf<Int>()
        val host = failedLoad(timers, loads) { if (it < 2) updating else FOUND }
        timers.retried(host)
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
        val host = failedLoad(timers, loads) { updating }
        timers.retried(host)
        host.onCancelJoin().join()
        timers.advanceBy(120)
        assertEquals(1, loads.size)
    }
}
