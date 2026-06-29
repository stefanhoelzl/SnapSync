package app.snapsync.presentation

import app.snapsync.config.ConfigDecodeResult
import app.snapsync.config.ConfigSource
import app.snapsync.config.ConfigStore
import app.snapsync.config.EventConfigPayload
import app.snapsync.config.decodeConfigUrl
import app.snapsync.config.encodeConfigUrl
import app.snapsync.eventcreation.CreateEvent
import app.snapsync.eventcreation.CreateOutcome
import app.snapsync.eventcreation.CreationFailureReason
import app.snapsync.eventcreation.CreationStatus
import app.snapsync.eventcreation.EventCreationClient
import app.snapsync.eventcreation.EventCreator
import app.snapsync.eventcreation.MutableCreationStatusSource
import app.snapsync.eventstatus.EventStatus
import app.snapsync.eventstatus.MutableEventStatusSource
import app.snapsync.permission.PermissionRequester
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PermissionStatusSource
import app.snapsync.status.SyncStatus
import app.snapsync.status.SyncProgress
import app.snapsync.status.SyncStatusSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.orbitmvi.orbit.test.test

// StateFlow fakes mirror the seam contract exactly: the current truth is available
// synchronously at construction, and every assignment is the whole truth. The fake knows its
// truth synchronously, so it seeds Ready and never shows Loading; `value` wraps in Ready for
// readable call sites.
private class FakeSyncStatusSource(initial: SyncStatus = SyncStatus.Ready(snapshot())) :
    SyncStatusSource {
    constructor(initial: SyncProgress) : this(SyncStatus.Ready(initial))

    private val flow = MutableStateFlow(initial)
    override val status: StateFlow<SyncStatus> = flow
    var value: SyncProgress
        get() = (flow.value as SyncStatus.Ready).progress
        set(v) {
            flow.value = SyncStatus.Ready(v)
        }
}

private class FakePermissionSource(
    initial: PermissionStatus = PermissionStatus.GRANTED,
) : PermissionStatusSource {
    override val permission = MutableStateFlow(initial)
}

// Config seam + store as one fake: save writes the cell, which is exactly how the real Keychain
// adapter behaves (its change arrives back via ConfigSource). Defaults to present so the sync-state
// tests fall through the setup gate.
private val SAMPLE_CONFIG = EventConfigPayload(
    eventId = "11111111-1111-4111-8111-111111111111",
)

private class FakeConfig(initial: EventConfigPayload? = SAMPLE_CONFIG) : ConfigSource, ConfigStore {
    private val flow = MutableStateFlow(initial)
    override val config: StateFlow<EventConfigPayload?> = flow
    override suspend fun save(config: EventConfigPayload) {
        flow.value = config
    }
    override suspend fun clear() {
        flow.value = null
    }
}

private class SpyRequester : PermissionRequester {
    var requests = 0
    var settingsOpens = 0

    override fun request() {
        requests++
    }

    override fun openSettings() {
        settingsOpens++
    }
}

private class SpyCreator : EventCreator {
    val created = mutableListOf<String>()
    override fun create(name: String) {
        created += name
    }
}

private fun snapshot(
    pending: Int = 0,
    completed: Int = 0,
    total: Int = 0,
    failed: Int = 0,
    active: Boolean = true,
    estimatedRemaining: Duration? = null,
) = SyncProgress(pending, completed, total, failed, active, estimatedRemaining)

private fun host(
    source: FakeSyncStatusSource,
    scope: CoroutineScope,
    permission: FakePermissionSource = FakePermissionSource(),
    requester: PermissionRequester = SpyRequester(),
    configFake: FakeConfig = FakeConfig(),
) = StatusContainerHost(source, permission, requester, configFake, configFake, scope)

class StatusContainerHostTest {

    @Test
    fun `fewer synced than present maps to InProgress with the counts`() = runTest {
        val source = FakeSyncStatusSource()
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(pending = 35, completed = 12, total = 47)
            expectState(UiState.InProgress(synced = 12, total = 47, inProgress = 35))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `virgin ledger with photos maps to InProgress zero of N`() = runTest {
        val source = FakeSyncStatusSource()
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 0, total = 5)
            expectState(UiState.InProgress(synced = 0, total = 5, inProgress = 0))
            cancelAndIgnoreRemainingItems()
        }
    }

    // The initial state is reduced synchronously from the sources' current values at construction, so
    // the join precedence is asserted directly on the container's first state.
    private fun joinHost(
        eventStatus: EventStatus,
        config: FakeConfig = FakeConfig(),
        permission: FakePermissionSource = FakePermissionSource(),
        scope: CoroutineScope,
    ) = StatusContainerHost(
        FakeSyncStatusSource(), permission, SpyRequester(), config, config, scope,
        eventStatusSource = MutableEventStatusSource(eventStatus),
    )

    @Test
    fun `joining outranks the sync hero once both gates pass`() = runTest {
        val host = joinHost(EventStatus.Joining, scope = backgroundScope)
        assertEquals(UiState.Joining, host.container.stateFlow.value)
    }

    @Test
    fun `join failed outranks the sync hero once both gates pass`() = runTest {
        val host = joinHost(EventStatus.JoinFailed, scope = backgroundScope)
        assertEquals(UiState.JoinFailed, host.container.stateFlow.value)
    }

    @Test
    fun `joined falls through to the sync hero`() = runTest {
        val host = joinHost(EventStatus.Joined, scope = backgroundScope)
        // Default snapshot is total 0 → NothingToSync (the join status does not outrank a settled join).
        assertEquals(UiState.NothingToSync, host.container.stateFlow.value)
    }

    @Test
    fun `create layer outranks a join in flight when config is absent`() = runTest {
        val host = joinHost(EventStatus.Joining, config = FakeConfig(null), scope = backgroundScope)
        assertEquals(UiState.CreateEvent(), host.container.stateFlow.value)
    }

    // The create layer is the top rung: config absent, reduced from the creation status.
    private fun createHost(
        creation: CreationStatus,
        permission: FakePermissionSource = FakePermissionSource(PermissionStatus.GRANTED),
        creator: EventCreator = SpyCreator(),
        scope: CoroutineScope,
    ): StatusContainerHost {
        val config = FakeConfig(null)
        return StatusContainerHost(
            FakeSyncStatusSource(), permission, SpyRequester(), config, config, scope,
            creationStatusSource = MutableCreationStatusSource(creation), creator = creator,
        )
    }

    @Test
    fun `config absent with idle creation shows the create input`() = runTest {
        val host = createHost(CreationStatus.Idle, scope = backgroundScope)
        assertEquals(UiState.CreateEvent(error = null), host.container.stateFlow.value)
    }

    @Test
    fun `config absent with in-flight creation shows the creating state`() = runTest {
        val host = createHost(CreationStatus.InFlight, scope = backgroundScope)
        assertEquals(UiState.CreatingEvent, host.container.stateFlow.value)
    }

    @Test
    fun `config absent with an invalid-name failure shows the input with the name error`() = runTest {
        val host = createHost(CreationStatus.Failed(CreationFailureReason.INVALID_NAME), scope = backgroundScope)
        assertEquals(UiState.CreateEvent(error = "That name isn't valid."), host.container.stateFlow.value)
    }

    @Test
    fun `config absent with a server failure shows the input with the server error`() = runTest {
        val host = createHost(CreationStatus.Failed(CreationFailureReason.SERVER), scope = backgroundScope)
        assertEquals(UiState.CreateEvent(error = "Couldn't reach the server."), host.container.stateFlow.value)
    }

    @Test
    fun `create layer ignores permission`() = runTest {
        val host = createHost(
            CreationStatus.Idle,
            permission = FakePermissionSource(PermissionStatus.DENIED),
            scope = backgroundScope,
        )
        // Config absent outranks a non-granted permission — still the create input, not PermissionBlocked.
        assertEquals(UiState.CreateEvent(), host.container.stateFlow.value)
    }

    @Test
    fun `onCreateEvent delegates to the creator`() = runTest {
        val creator = SpyCreator()
        val host = createHost(CreationStatus.Idle, creator = creator, scope = backgroundScope)
        host.test(this) {
            containerHost.onCreateEvent("My Party")
        }
        advanceUntilIdle()
        assertEquals(listOf("My Party"), creator.created)
    }

    // Integration: the real CreateEvent use-case wired into the real container reduction. (The
    // event-creation-ui → presentation dependency is allowed in production, so this needs no special
    // boundary-crossing module — just the assembled stack with the network edge faked.)
    private class StubClient(private val outcome: CreateOutcome) : EventCreationClient {
        override suspend fun create(name: String) = outcome
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a successful create flows through the use-case to a joined downstream state`() = runTest {
        val config = FakeConfig(null)
        val creationStatus = MutableCreationStatusSource()
        val eventStatus = MutableEventStatusSource()
        // Eager dispatcher so the fire-and-forget create mutates the seams synchronously.
        val creator = CreateEvent(
            client = StubClient(CreateOutcome.Created("evt-1")),
            status = creationStatus,
            // The provision path a scanned QR also takes: config goes present and the join starts.
            provision = { config.save(EventConfigPayload("evt-1")); eventStatus.set(EventStatus.Joining) },
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        val host = StatusContainerHost(
            FakeSyncStatusSource(), FakePermissionSource(PermissionStatus.GRANTED), SpyRequester(),
            config, config, backgroundScope,
            eventStatusSource = eventStatus, creationStatusSource = creationStatus, creator = creator,
        )
        host.test(this) {
            runOnCreate()
            // Drive the real use-case (the onCreateEvent → creator hop is covered separately).
            creator.create("My Party")
            // minted → provisioned (config present + join started) → the downstream Joining state.
            expectState(UiState.Joining)
            cancelAndIgnoreRemainingItems()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a failed create surfaces the inline error and stays on the create layer`() = runTest {
        val config = FakeConfig(null)
        val creationStatus = MutableCreationStatusSource()
        var provisioned = false
        val creator = CreateEvent(
            client = StubClient(CreateOutcome.Transient),
            status = creationStatus,
            provision = { provisioned = true },
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        val host = StatusContainerHost(
            FakeSyncStatusSource(), FakePermissionSource(PermissionStatus.GRANTED), SpyRequester(),
            config, config, backgroundScope,
            creationStatusSource = creationStatus, creator = creator,
        )
        host.test(this) {
            runOnCreate()
            creator.create("My Party")
            expectState(UiState.CreateEvent(error = "Couldn't reach the server."))
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(false, provisioned) // config never flipped
    }

    @Test
    fun `empty library maps to NothingToSync`() = runTest {
        // Seed a non-empty state so the transition to total=0 is a real change (equal UI states
        // are conflated by the container's StateFlow).
        val source = FakeSyncStatusSource(snapshot(completed = 1, total = 3))
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 0, total = 0)
            expectState(UiState.NothingToSync)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `all present photos synced maps to Completed`() = runTest {
        val source = FakeSyncStatusSource()
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 34, total = 34)
            expectState(UiState.Completed(total = 34))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `overshoot clamps the displayed synced count and maps to Completed`() = runTest {
        val source = FakeSyncStatusSource()
        host(source, backgroundScope).test(this) {
            runOnCreate()
            // A deleted photo still COMPLETED in the ledger: completed 6 over a live total of 5.
            source.value = snapshot(completed = 6, total = 5)
            expectState(UiState.Completed(total = 5))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `a newer snapshot replaces the displayed state entirely`() = runTest {
        val source = FakeSyncStatusSource()
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 1, total = 10)
            expectState(UiState.InProgress(synced = 1, total = 10, inProgress = 0))
            source.value = snapshot(completed = 5, total = 10)
            expectState(UiState.InProgress(synced = 5, total = 10, inProgress = 0))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `initial state derives from source values and never a guess`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34))
        val container = host(source, backgroundScope).container

        assertEquals(UiState.Completed(total = 34), container.stateFlow.value)
    }

    @Test
    fun `denied permission with config present blocks over any sync snapshot`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34))
        val permission = FakePermissionSource(PermissionStatus.DENIED)
        val container = host(source, backgroundScope, permission = permission).container

        assertEquals(UiState.PermissionBlocked(PermissionStatus.DENIED), container.stateFlow.value)
    }

    @Test
    fun `not-determined permission with config present blocks the hero`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34))
        val permission = FakePermissionSource(PermissionStatus.NOT_DETERMINED)
        val container = host(source, backgroundScope, permission = permission).container

        assertEquals(UiState.PermissionBlocked(PermissionStatus.NOT_DETERMINED), container.stateFlow.value)
    }

    @Test
    fun `permission outranks a join in flight`() = runTest {
        val host = joinHost(
            EventStatus.Joining,
            permission = FakePermissionSource(PermissionStatus.DENIED),
            scope = backgroundScope,
        )
        assertEquals(UiState.PermissionBlocked(PermissionStatus.DENIED), host.container.stateFlow.value)
    }

    @Test
    fun `revoking permission mid-sync blocks the running hero`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34))
        val permission = FakePermissionSource(PermissionStatus.GRANTED)
        host(source, backgroundScope, permission = permission).test(this) {
            runOnCreate()
            // Synced and showing the hero, then access is revoked in system Settings.
            permission.permission.value = PermissionStatus.DENIED
            expectState(UiState.PermissionBlocked(PermissionStatus.DENIED))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `absent config shows the create layer even when granted`() = runTest {
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        val permission = FakePermissionSource(PermissionStatus.GRANTED)
        val container =
            host(source, backgroundScope, permission = permission, configFake = FakeConfig(null)).container

        assertEquals(UiState.CreateEvent(), container.stateFlow.value)
    }

    @Test
    fun `loading snapshot with config and granted permission maps to Loading`() = runTest {
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        val permission = FakePermissionSource(PermissionStatus.GRANTED)
        val container = host(source, backgroundScope, permission = permission).container

        assertEquals(UiState.Loading, container.stateFlow.value)
    }

    @Test
    fun `permission blocks a loading snapshot when config is present`() = runTest {
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        val permission = FakePermissionSource(PermissionStatus.NOT_DETERMINED)
        val container = host(source, backgroundScope, permission = permission).container

        assertEquals(UiState.PermissionBlocked(PermissionStatus.NOT_DETERMINED), container.stateFlow.value)
    }

    @Test
    fun `absent config outranks a loading snapshot`() = runTest {
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        val permission = FakePermissionSource(PermissionStatus.NOT_DETERMINED)
        val container =
            host(source, backgroundScope, permission = permission, configFake = FakeConfig(null)).container

        assertEquals(UiState.CreateEvent(), container.stateFlow.value)
    }

    @Test
    fun `both gates satisfied reveals the current sync state`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34))
        val permission = FakePermissionSource(PermissionStatus.NOT_DETERMINED)
        host(source, backgroundScope, permission = permission).test(this) {
            runOnCreate()
            permission.permission.value = PermissionStatus.GRANTED
            expectState(UiState.Completed(total = 34))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `a valid deeplink saves config and advances the gate`() = runTest {
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        val permission = FakePermissionSource(PermissionStatus.GRANTED)
        val configFake = FakeConfig(null)
        host(source, backgroundScope, permission = permission, configFake = configFake).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeConfigUrl(SAMPLE_CONFIG))
            // config now present + granted + Loading snapshot -> Loading
            expectState(UiState.Loading)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `an invalid deeplink emits the transient error and changes nothing`() = runTest {
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        val permission = FakePermissionSource(PermissionStatus.GRANTED)
        host(source, backgroundScope, permission = permission, configFake = FakeConfig(null)).test(this) {
            runOnCreate()
            containerHost.onOpenUrl("not a config link")
            expectSideEffect(SetupEffect.InvalidConfigLink)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `intents pass through to the requester`() = runTest {
        val permission = FakePermissionSource(PermissionStatus.NOT_DETERMINED)
        val requester = SpyRequester()
        host(FakeSyncStatusSource(), backgroundScope, permission = permission, requester = requester)
            .test(this) {
                containerHost.onRequestPermission()
                containerHost.onOpenSettings()
            }
        advanceUntilIdle()

        assertEquals(1, requester.requests)
        assertEquals(1, requester.settingsOpens)
    }

    @Test
    fun `onLeaveEvent invokes the injected leave action`() = runTest {
        var leaves = 0
        val configFake = FakeConfig()
        val containerHost = StatusContainerHost(
            FakeSyncStatusSource(), FakePermissionSource(), SpyRequester(), configFake, configFake,
            backgroundScope, leave = { leaves++ },
        )
        containerHost.test(this) {
            containerHost.onLeaveEvent()
        }
        advanceUntilIdle()

        assertEquals(1, leaves)
    }

    @Test
    fun `onLeaveEvent with the default no-op leave is inert`() = runTest {
        // Construction without injecting a leave action succeeds, and a confirmed leave does nothing.
        host(FakeSyncStatusSource(), backgroundScope).test(this) {
            containerHost.onLeaveEvent()
        }
        advanceUntilIdle()
    }

    @Test
    fun `invite url derives from config and round-trips to the same event`() = runTest {
        val configFake = FakeConfig(SAMPLE_CONFIG)
        val host = StatusContainerHost(
            FakeSyncStatusSource(), FakePermissionSource(), SpyRequester(), configFake, configFake,
            backgroundScope,
        )
        // Same URL a scanner of the event's QR would receive, and it decodes back to the same eventId.
        assertEquals(encodeConfigUrl(SAMPLE_CONFIG), host.inviteUrl.value)
        val decoded = decodeConfigUrl(host.inviteUrl.value!!)
        assertTrue(decoded is ConfigDecodeResult.Success)
        assertEquals(SAMPLE_CONFIG.eventId, decoded.payload.eventId)
    }

    @Test
    fun `invite url is null when no event is configured`() = runTest {
        val configFake = FakeConfig(null)
        val host = StatusContainerHost(
            FakeSyncStatusSource(), FakePermissionSource(), SpyRequester(), configFake, configFake,
            backgroundScope,
        )
        assertEquals(null, host.inviteUrl.value)
    }

    @Test
    fun `onShareInvite hands the invite url to the injected share`() = runTest {
        val shared = mutableListOf<String>()
        val configFake = FakeConfig(SAMPLE_CONFIG)
        val containerHost = StatusContainerHost(
            FakeSyncStatusSource(), FakePermissionSource(), SpyRequester(), configFake, configFake,
            backgroundScope, share = { shared += it },
        )
        containerHost.test(this) {
            containerHost.onShareInvite()
        }
        advanceUntilIdle()

        assertEquals(listOf(encodeConfigUrl(SAMPLE_CONFIG)), shared)
    }

    @Test
    fun `onShareInvite with no configured event does not share`() = runTest {
        val shared = mutableListOf<String>()
        val configFake = FakeConfig(null)
        val containerHost = StatusContainerHost(
            FakeSyncStatusSource(), FakePermissionSource(), SpyRequester(), configFake, configFake,
            backgroundScope, share = { shared += it },
        )
        containerHost.test(this) {
            containerHost.onShareInvite()
        }
        advanceUntilIdle()

        assertTrue(shared.isEmpty())
    }

    @Test
    fun `onShareInvite with the default no-op share is inert`() = runTest {
        // Construction without injecting a share action succeeds, and a share does nothing.
        host(FakeSyncStatusSource(), backgroundScope).test(this) {
            containerHost.onShareInvite()
        }
        advanceUntilIdle()
    }

    @Test
    fun `observing NOT_DETERMINED never auto-requests`() = runTest {
        val permission = FakePermissionSource(PermissionStatus.NOT_DETERMINED)
        val requester = SpyRequester()
        host(FakeSyncStatusSource(), backgroundScope, permission = permission, requester = requester)
            .test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }
        advanceUntilIdle()

        assertEquals(0, requester.requests)
    }
}
