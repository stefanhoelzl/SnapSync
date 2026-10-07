@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.presentation

import app.snapsync.feature.creation.readmodel.CreationFailureReason
import app.snapsync.feature.creation.readmodel.CreationStatus
import app.snapsync.feature.status.readmodel.NetworkStatusSource
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.model.Arrow
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.EventStart
import app.snapsync.model.GalleryAccess
import app.snapsync.model.JoinLoad
import app.snapsync.model.JoinPhase
import app.snapsync.model.Layer
import app.snapsync.model.NetworkAccess
import app.snapsync.model.NetworkNotice
import app.snapsync.model.ScreenMessage
import app.snapsync.model.SyncHealth
import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.encodeEventUrl
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private class FakeNetwork(initial: NetworkAccess = NetworkAccess.Online(restricted = false)) : NetworkStatusSource {
        override val access = MutableStateFlow(initial)
        val returns = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val returned: Flow<Unit> = returns

        /** A shown notice clearing — what the watch publishes on a return. */
        fun comeBack() {
            access.value = NetworkAccess.Online(restricted = false)
            returns.tryEmit(Unit)
        }
    }

    private class Sync(initial: SyncStatus) : SyncStatusSource {
        override val status: StateFlow<SyncStatus> = MutableStateFlow(initial)
    }

    private fun TestScope.host(
        network: FakeNetwork,
        config: EventConfig? = STARTED,
        permission: GalleryAccess = GalleryAccess.GRANTED,
        attested: Boolean = true,
        creation: CreationStatus = CreationStatus.Idle,
        load: suspend (String) -> JoinLoad = { JoinLoad.Failed },
        scope: CoroutineScope = backgroundScope,
        progress: SyncProgress = SyncProgress(0, 0, 0, 0, false, null),
        mobileData: Boolean = true,
    ) = StatusContainerHost(
        StatusSources(
            Sync(SyncStatus.Ready(progress)),
            MutableStateFlow(permission),
            MutableStateFlow(config),
            creation = MutableStateFlow(creation),
            verification = DeviceVerification(MutableStateFlow(attested)),
            network = network,
            mobileData = MutableStateFlow(mobileData),
        ),
        scope,
        queries = joinDetails(load),
        commands = testCommands(),
        cutoffFormatter = CutoffFormatter(now = { NOW }, zone = TimeZone.UTC),
        diagnostics = testDiagnostics(),
    )

    /** [block] with the container's intents running, as on a screen — Orbit starts them on create. */
    private suspend fun TestScope.driving(host: StatusContainerHost, block: suspend () -> Unit) =
        host.testWithInternalState(this) {
            runOnCreate()
            runCurrent()
            block()
            cancelAndIgnoreRemainingItems()
        }

    private fun StatusContainerHost.health(): SyncHealth = (container.stateFlow.value.layer as Layer.Joined).health

    // ── the joined status line ───────────────────────────────────────────────────────────────

    @Test
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
        val host = host(FakeNetwork(NetworkAccess.Offline), permission = GalleryAccess.DENIED)
        assertEquals(SyncHealth.NeedsAccess(GalleryAccess.DENIED), host.health())
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
            host(FakeNetwork(), config = neither, permission = GalleryAccess.DENIED).health(),
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
            SyncProgress(pending = 1, completed = 0, total = 1, failed = 0, active = true, estimatedRemaining = null)
        val health = host(
            FakeNetwork(),
            config = STARTED.copy(direction = Direction.Neither),
            progress = draining,
        ).health()
        assertTrue(health is SyncHealth.Syncing, "an upload still under way is progress, not \"not sharing\": $health")
        // …and with nothing left, the line says the member moves nothing — with no counts beneath it.
        val settled = host(FakeNetwork(), config = STARTED.copy(direction = Direction.Neither))
        assertEquals(null, (settled.container.stateFlow.value.layer as Layer.Joined).counts)
    }

    // ── photos kept off mobile data (capability `mobile-data`) ────────────────────────────────

    /** One upload handed to the platform and not yet done: shown, and in flight. */
    private val oneUploading =
        SyncProgress(pending = 1, completed = 0, total = 1, failed = 0, active = true, estimatedRemaining = null)

    @Test
    fun `photos kept off mobile data on a restricted network wait for Wi-Fi with still arrows`() = runTest {
        // The device's choice, not the membership's (decision record `changes/archive/2026-10-07-mobile-data-per-device`).
        val host =
            host(FakeNetwork(NetworkAccess.Online(restricted = true)), progress = oneUploading, mobileData = false)
        assertEquals(SyncHealth.Syncing(Arrow.STATIC, Arrow.HIDDEN, waitingForWifi = true), host.health())
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
            Layer.CreateEvent(network = NetworkNotice.OFFLINE),
            host(FakeNetwork(NetworkAccess.Offline), config = null).container.stateFlow.value.layer,
        )
    }

    @Test
    fun `a failed create's message is kept under the notice and returns with the network`() = runTest {
        val network = FakeNetwork(NetworkAccess.Offline)
        val host = host(network, config = null, creation = CreationStatus.Failed(CreationFailureReason.SERVER))
        driving(host) {
            assertEquals(
                Layer.CreateEvent(error = ScreenMessage.CREATE_FAILED, network = NetworkNotice.OFFLINE),
                host.container.stateFlow.value.layer,
            )
            network.comeBack()
            runCurrent()
            assertEquals(Layer.CreateEvent(error = ScreenMessage.CREATE_FAILED), host.container.stateFlow.value.layer)
        }
    }

    @Test
    fun `a create in flight is not interrupted`() = runTest {
        assertEquals(
            Layer.CreatingEvent,
            host(
                FakeNetwork(NetworkAccess.Offline),
                config = null,
                creation = CreationStatus.InFlight,
            ).container.stateFlow.value.layer,
        )
    }

    // ── the join layer ───────────────────────────────────────────────────────────────────────

    @Test
    fun `an invite opened offline loads by itself once the network returns`() = runTest {
        val network = FakeNetwork(NetworkAccess.Offline)
        var loads = 0
        val host = host(network, config = null, load = { if (loads++ == 0) JoinLoad.Failed else FOUND })
        driving(host) {
            host.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT)))
            runCurrent()
            val offline = assertIs<Layer.JoiningEvent>(host.container.stateFlow.value.layer)
            assertEquals(JoinPhase.LoadFailed, offline.phase)
            assertEquals(NetworkNotice.OFFLINE, offline.network)

            network.comeBack()
            runCurrent()
            val loaded = assertIs<Layer.JoiningEvent>(host.container.stateFlow.value.layer)
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
            JoinLoad.Failed
        })
        driving(host) {
            host.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT)))
            runCurrent()
            assertEquals(JoinPhase.LoadFailed, assertIs<Layer.JoiningEvent>(host.container.stateFlow.value.layer).phase)
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
            host.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT)))
            runCurrent()
            network.access.value = NetworkAccess.Blocked
            runCurrent()
            val layer = assertIs<Layer.JoiningEvent>(host.container.stateFlow.value.layer)
            assertIs<JoinPhase.Detailed>(layer.phase)
            assertEquals(NetworkNotice.BLOCKED, layer.network)
        }
    }

    private companion object {
        const val EVENT = "11111111-1111-4111-8111-111111111111"
        val NOW: Instant = Instant.parse("2026-07-09T12:00:00Z")
        val STARTED = EventConfig(
            EVENT,
            "Party",
            captureCutoff("2026-07-06T00:00:00Z"),
            maxPhotoDate = captureCeiling("2026-07-13T00:00:00Z"),
            endsAt = eventEnd("2099-12-31T00:00:00Z"),
            deletesAt = deletesAt("2099-12-31T00:00:00Z"),
        )
        val NOT_STARTED = EventConfig(
            EVENT,
            "Party",
            captureCutoff("2026-07-10T00:00:00Z"),
            startsAt = EventStart(captureCutoff("2026-07-10T00:00:00Z").at),
            maxPhotoDate = captureCeiling("2026-07-13T00:00:00Z"),
            endsAt = eventEnd("2099-12-31T00:00:00Z"),
            deletesAt = deletesAt("2099-12-31T00:00:00Z"),
        )
        val FOUND = JoinLoad.Found(
            "Party",
            eventStart("2026-07-06T00:00:00Z"),
            eventEnd("2026-07-13T00:00:00Z"),
            deletesAt("2026-08-05T00:00:00Z"),
        )
    }
}
