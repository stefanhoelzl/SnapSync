@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.presentation

import app.snapsync.control.Verifies
import app.snapsync.domain.event.CreateResult
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.domain.network.NetworkAccess
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.sharing.SharingProgress
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.presentation.JoinGateRules.settled
import app.snapsync.presentation.Switches.mobileData
import app.snapsync.query.RequestStatus
import app.snapsync.query.membership.JoinDetails
import app.snapsync.query.mobiledata.MobileDataQuery
import app.snapsync.query.mobiledata.MobileDataStatuses
import app.snapsync.query.network.NetworkStatusQuery
import app.snapsync.query.receiving.DownloadProgress
import app.snapsync.testids.testEventId
import app.snapsync.ui.state.Arrow
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.NetworkNotice
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.SyncHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.orbitmvi.orbit.test.testWithInternalState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The network notice in the reduction (capabilities `sync-status`, `create-event`, `join-event`; decision record
 * `changes/tell-when-offline`, D3–D4): its rung in the joined status line, its place on the create and join layers, and
 * the join reload the network's return triggers — and only that return.
 */
class StatusContainerHostNetworkTest {

    private class FakeNetwork(initial: NetworkAccess = NetworkAccess.Online(restricted = false)) : NetworkStatusQuery {
        override val access = MutableStateFlow(initial)
        val returns = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val returned: Flow<Unit> = returns

        /** A shown notice clearing — what the watch publishes on a return. */
        fun comeBack() {
            access.value = NetworkAccess.Online(restricted = false)
            returns.tryEmit(Unit)
        }
    }

    private fun Sync(initial: SharingProgressRead): StateFlow<SharingProgressRead> = MutableStateFlow(initial)

    private fun TestScope.host(
        network: FakeNetwork,
        config: TestMembership? = STARTED,
        permission: PhotoGrant = PhotoGrant.FULL,
        attested: Boolean = true,
        creation: RequestStatus<CreateResult> = Creating.Idle,
        load: suspend (String) -> JoinDetails = { JoinDetails.Unanswered(ServiceFault.UNREACHABLE) },
        scope: CoroutineScope = backgroundScope,
        progress: SharingProgress = SharingProgress(shared = 0, total = 0, handedOver = 0),
        mobileData: Boolean = true,
        download: DownloadProgress = DownloadProgress(0, 0),
    ) = TestCommands().let { commands ->
        testHost(
            testQueries(
                Sync(SharingProgressRead.Read(progress)),
                MutableStateFlow(permission),
                MutableStateFlow(config).held(),
                creation = MutableStateFlow(creation),
                download = MutableStateFlow(download),
                verification = testVerification(MutableStateFlow(attested)),
                network = network,
                mobileData = object : MobileDataQuery {
                    override fun current() = if (mobileData) MobileDataChoice.ALLOWED else MobileDataChoice.WIFI_ONLY
                    override val changes = MobileDataStatuses().change.status
                },
                requests = commands.requests.flows,
            ),
            scope,
            joinDetails = joinDetails(load),
            commands = commands.door,
            cutoffFormatter = CutoffFormatter(now = instants({ NOW }), zone = TimeZone.UTC),
            shareableCount = noQueries,
            reduction = StandardTestDispatcher(testScheduler),
        )
    }

    /** [block] with the container's intents running, as on a screen — Orbit starts them on create. */
    private suspend fun TestScope.driving(host: StatusContainerHost, block: suspend () -> Unit) =
        host.testWithInternalState(this) {
            runOnCreate()
            runCurrent()
            block()
            cancelAndIgnoreRemainingItems()
        }

    private fun StatusContainerHost.health(): SyncHealth = (screen.value.layer as Layer.Joined).health

    // ── the joined status line ───────────────────────────────────────────────────────────────

    // The layer's part of "Offline": no network reads as the offline line. That the line offers no tap is the screen's
    // (`StatusScreenTest`).
    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "Only missing access and a blocked network make the status line tappable",
        scenario = "Offline",
    )
    fun `a missing network is the status line with its cause`() = runTest {
        for ((access, notice) in listOf(
            NetworkAccess.Offline to NetworkNotice.OFFLINE,
            NetworkAccess.Blocked to NetworkNotice.BLOCKED,
        )) {
            assertEquals(SyncHealth.NoNetwork(notice), host(FakeNetwork(access)).health())
        }
    }

    @Test
    fun `missing access outranks a missing network`() = runTest {
        val host = host(FakeNetwork(NetworkAccess.Offline), permission = PhotoGrant.REFUSED)
        assertEquals(SyncHealth.NeedsAccess(canAsk = false), host.health())
    }

    @Test
    fun `a missing network outranks a future start and an in-sync device`() = runTest {
        assertEquals(
            SyncHealth.NoNetwork(NetworkNotice.OFFLINE),
            host(FakeNetwork(NetworkAccess.Offline), config = NOT_STARTED).health(),
        )
        val inSync = host(FakeNetwork())
        assertEquals(SyncHealth.InSync, inSync.health(), "online, the same device is in sync")
    }

    @Test
    fun `offline with an expired verification says offline and not cannot-verify`() = runTest {
        assertEquals(
            SyncHealth.NoNetwork(NetworkNotice.OFFLINE),
            host(FakeNetwork(NetworkAccess.Offline), attested = false).health(),
        )
        assertEquals(
            SyncHealth.Unattested(),
            host(FakeNetwork(), attested = false).health(),
            "online, the server is to blame",
        )
    }

    @Test
    fun `the line clears as soon as the network returns`() = runTest {
        val network = FakeNetwork(NetworkAccess.Blocked)
        val host = host(network)
        driving(host) {
            network.comeBack()
            runCurrent()
            assertEquals(SyncHealth.InSync, host.health())
        }
    }

    // ── neither sharing nor receiving (capability `sync-status`) ──────────────────────────────

    @Test
    fun `a membership that neither shares nor receives says so ahead of every attention line`() = runTest {
        val neither = STARTED.copy(direction = Direction.Neither)
        assertEquals(SyncHealth.Inactive, host(FakeNetwork(), config = neither).health())
        // Access, the network, the start and verification all concern photos that no longer move.
        assertEquals(
            SyncHealth.Inactive,
            host(FakeNetwork(), config = neither, permission = PhotoGrant.REFUSED).health(),
        )
        assertEquals(SyncHealth.Inactive, host(FakeNetwork(NetworkAccess.Offline), config = neither).health())
        assertEquals(
            SyncHealth.Inactive,
            host(FakeNetwork(), config = NOT_STARTED.copy(direction = Direction.Neither)).health(),
        )
        assertEquals(SyncHealth.Inactive, host(FakeNetwork(), config = neither, attested = false).health())
    }

    @Test
    fun `work still draining after both directions are off is shown and not masked`() = runTest {
        val draining =
            SharingProgress(shared = 0, total = 1, handedOver = 1)
        val health = host(
            FakeNetwork(),
            config = STARTED.copy(direction = Direction.Neither),
            progress = draining,
        ).health()
        assertTrue(health is SyncHealth.Syncing, "an upload still under way is progress, not \"not sharing\": $health")
        // …and with nothing left, the line says the member moves nothing — with no counts beneath it.
        val settled = host(FakeNetwork(), config = STARTED.copy(direction = Direction.Neither))
        assertEquals(null, (settled.screen.value.layer as Layer.Joined).counts)
    }

    // ── photos kept off mobile data (capability `mobile-data`) ────────────────────────────────

    /** One upload handed to the platform and not yet done: shown, and in flight. */
    private val oneUploading =
        SharingProgress(shared = 0, total = 1, handedOver = 1)

    @Test
    fun `photos kept off mobile data on a restricted network wait for Wi-Fi with still arrows`() = runTest {
        // The device's choice, not the membership's (decision record
        // `changes/archive/2026-10-07-mobile-data-per-device`).
        val host =
            host(FakeNetwork(NetworkAccess.Online(restricted = true)), progress = oneUploading, mobileData = false)
        assertEquals(SyncHealth.Syncing(Arrow.STATIC, Arrow.HIDDEN, waitingForWifi = true), host.health())
    }

    @Test
    fun `a photo going into the gallery pulses while photos wait for Wi-Fi`() = runTest {
        // Its bytes are here already: the import needs no network, so the choice holds nothing of it back. The other
        // download is still held, and says so — but a pulse outranks it, so the line reads "on their way".
        val host = host(
            FakeNetwork(NetworkAccess.Online(restricted = true)),
            progress = SharingProgress(shared = 1, total = 1, handedOver = 0),
            mobileData = false,
            download = DownloadProgress(downloaded = 1, total = 3, inFlight = 1, importing = 1),
        )
        assertEquals(SyncHealth.Syncing(Arrow.HIDDEN, Arrow.PULSING, waitingForWifi = true), host.health())
    }

    @Test
    fun `only the others left outranks waiting for Wi-Fi`() = runTest {
        // Nothing of this phone's own is held for Wi-Fi: its photos are all shared, and all offered here have arrived.
        val host = host(
            FakeNetwork(NetworkAccess.Online(restricted = true)),
            progress = SharingProgress(shared = 1, total = 1, handedOver = 0),
            mobileData = false,
            download = DownloadProgress(downloaded = 6, total = 6, awaited = 10),
        )
        assertEquals(SyncHealth.Syncing(Arrow.HIDDEN, Arrow.STATIC, waitingForOthers = true), host.health())
    }

    @Test
    fun `photos allowed on mobile data or a member on Wi-Fi do not wait`() = runTest {
        val onMobileData = host(FakeNetwork(NetworkAccess.Online(restricted = true)), progress = oneUploading)
        assertEquals(SyncHealth.Syncing(Arrow.PULSING, Arrow.HIDDEN), onMobileData.health(), "the choice is on")
        val onWifi = host(FakeNetwork(), progress = oneUploading, mobileData = false)
        assertEquals(SyncHealth.Syncing(Arrow.PULSING, Arrow.HIDDEN), onWifi.health(), "the network is unrestricted")
    }

    // ── the create layer ─────────────────────────────────────────────────────────────────────

    @Test
    fun `offline the create layer carries the notice`() = runTest {
        assertEquals(
            Layer.CreateEvent(
                freshCreateForm(CutoffFormatter(now = instants({ NOW }), zone = TimeZone.UTC)),
                network = NetworkNotice.OFFLINE,
            ),
            host(FakeNetwork(NetworkAccess.Offline), config = null).screen.value.layer,
        )
    }

    @Test
    fun `a failed create's message is kept under the notice and returns with the network`() = runTest {
        val network = FakeNetwork(NetworkAccess.Offline)
        val host = host(network, config = null, creation = Creating.answered(CreateResult.Unverified))
        driving(host) {
            assertEquals(
                Layer.CreateEvent(
                    freshCreateForm(CutoffFormatter(now = instants({ NOW }), zone = TimeZone.UTC)),
                    error = ScreenMessage.CREATE_FAILED,
                    network = NetworkNotice.OFFLINE,
                ),
                host.screen.value.layer,
            )
            network.comeBack()
            runCurrent()
            assertEquals(
                Layer.CreateEvent(
                    freshCreateForm(CutoffFormatter(now = instants({ NOW }), zone = TimeZone.UTC)),
                    error = ScreenMessage.CREATE_FAILED,
                ),
                host.screen.value.layer,
            )
        }
    }

    @Test
    fun `a create in flight is not interrupted`() = runTest {
        assertEquals(
            Layer.CreatingEvent,
            host(
                FakeNetwork(NetworkAccess.Offline),
                config = null,
                creation = Creating.InFlight,
            ).screen.value.layer,
        )
    }

    // ── the join layer ───────────────────────────────────────────────────────────────────────

    @Test
    fun `an invite opened offline loads by itself once the network returns`() = runTest {
        val network = FakeNetwork(NetworkAccess.Offline)
        var loads = 0
        val host =
            host(
                network,
                config = null,
                load = { if (loads++ == 0) JoinDetails.Unanswered(ServiceFault.UNREACHABLE) else FOUND },
            )
        driving(host) {
            host.onInvite(invited(testEventId(EVENT), null))
            runCurrent()
            val offline = assertIs<Layer.JoiningEvent>(host.screen.value.layer)
            assertEquals(JoinPhase.LoadFailed, offline.phase)
            assertEquals(NetworkNotice.OFFLINE, offline.network)

            network.comeBack()
            runCurrent()
            val loaded = assertIs<Layer.JoiningEvent>(host.screen.value.layer)
            assertIs<JoinPhase.Detailed>(loaded.phase, "the details loaded without a tap")
            assertEquals(null, loaded.network)
            assertEquals(2, loads)
        }
    }

    @Test
    fun `a load that failed while online waits for Retry`() = runTest {
        val network = FakeNetwork()
        var loads = 0
        val host = host(network, config = null, load = {
            loads++
            JoinDetails.Unanswered(ServiceFault.UNREACHABLE)
        })
        driving(host) {
            host.onInvite(invited(testEventId(EVENT), null))
            runCurrent()
            assertEquals(JoinPhase.LoadFailed, assertIs<Layer.JoiningEvent>(host.screen.value.layer).phase)
            network.access.value = NetworkAccess.Online(restricted = false) // no return: nothing was missing
            runCurrent()
            assertEquals(1, loads, "an unreachable server is retried by the member, not by the network watch")
        }
    }

    @Test
    fun `offline after the details loaded the join layer carries the notice`() = runTest {
        val network = FakeNetwork()
        val host = host(network, config = null, load = { FOUND })
        driving(host) {
            host.onInvite(invited(testEventId(EVENT), null))
            runCurrent()
            network.access.value = NetworkAccess.Blocked
            runCurrent()
            val layer = assertIs<Layer.JoiningEvent>(host.screen.value.layer)
            assertIs<JoinPhase.Detailed>(layer.phase)
            assertEquals(NetworkNotice.BLOCKED, layer.network)
        }
    }

    private companion object {
        const val EVENT = "11111111-1111-4111-8111-111111111111"
        val NOW: Instant = Instant.parse("2026-07-09T12:00:00Z")
        val STARTED = TestMembership(
            testEventId(EVENT),
            "Party",
            CaptureCutoff(timestamp("2026-07-06T00:00:00Z")),
            maxPhotoDate = CaptureCeiling(timestamp("2026-07-13T00:00:00Z")),
            endsAt = EventEnd(timestamp("2099-12-31T00:00:00Z")),
            deletesAt = DeletesAt(timestamp("2099-12-31T00:00:00Z")),
        )
        val NOT_STARTED = TestMembership(
            testEventId(EVENT),
            "Party",
            CaptureCutoff(timestamp("2026-07-10T00:00:00Z")),
            startsAt = EventStart(CaptureCutoff(timestamp("2026-07-10T00:00:00Z")).at),
            maxPhotoDate = CaptureCeiling(timestamp("2026-07-13T00:00:00Z")),
            endsAt = EventEnd(timestamp("2099-12-31T00:00:00Z")),
            deletesAt = DeletesAt(timestamp("2099-12-31T00:00:00Z")),
        )
        val FOUND = found(
            "Party",
            EventStart(timestamp("2026-07-06T00:00:00Z")),
            EventEnd(timestamp("2026-07-13T00:00:00Z")),
            DeletesAt(timestamp("2026-08-05T00:00:00Z")),
        )
    }
}
