package app.snapsync.presentation

import app.snapsync.config.ConfigDecodeResult
import app.snapsync.config.ConfigSource
import app.snapsync.config.ConfigStore
import app.snapsync.config.EventConfig
import app.snapsync.config.EventLinkPayload
import app.snapsync.config.decodeConfigUrl
import app.snapsync.config.encodeConfigUrl
import app.snapsync.eventcreation.CreateEvent
import app.snapsync.eventcreation.CreateOutcome
import app.snapsync.eventcreation.CreationFailureReason
import app.snapsync.eventcreation.CreationStatus
import app.snapsync.eventcreation.EventCreationClient
import app.snapsync.eventcreation.EventCreator
import app.snapsync.eventcreation.MutableCreationStatusSource
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

private const val EVENT_ID = "11111111-1111-4111-8111-111111111111"

// Joined-state helpers keep the assertions readable.
private fun syncing(up: Arrow, down: Arrow = Arrow.HIDDEN) = UiState.Joined(SyncHealth.Syncing(up, down))
private val inSync = UiState.Joined(SyncHealth.InSync)
private val joinedLoading = UiState.Joined(SyncHealth.Loading)
private fun needsAccess(p: PermissionStatus) = UiState.Joined(SyncHealth.NeedsAccess(p))

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
// adapter behaves. Defaults to present so the sync-state tests reach the joined layer.
private val SAMPLE_CONFIG = EventConfig(eventId = EVENT_ID, name = "Anna's Birthday")

private class FakeConfig(initial: EventConfig? = SAMPLE_CONFIG) : ConfigSource, ConfigStore {
    private val flow = MutableStateFlow(initial)
    override val config: StateFlow<EventConfig?> = flow
    override suspend fun save(config: EventConfig) {
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
    fun `work in flight maps to Syncing with a pulsing up arrow`() = runTest {
        val source = FakeSyncStatusSource()
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(pending = 35, completed = 12, total = 47)
            expectState(syncing(up = Arrow.PULSING)) // completed<total, pending>0
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `work remaining but no jobs yet maps to Syncing with a static up arrow`() = runTest {
        val source = FakeSyncStatusSource()
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 0, total = 5) // pending 0
            expectState(syncing(up = Arrow.STATIC))
            cancelAndIgnoreRemainingItems()
        }
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
        // Config absent outranks a non-granted permission — still the create input, not the joined layer.
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

    private class StubClient(private val outcome: CreateOutcome) : EventCreationClient {
        override suspend fun create(name: String) = outcome
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a successful create flows through the use-case to the joined layer`() = runTest {
        val config = FakeConfig(null)
        val creationStatus = MutableCreationStatusSource()
        val creator = CreateEvent(
            client = StubClient(CreateOutcome.Created(EVENT_ID, name = "My Party")),
            status = creationStatus,
            // The provision path a scanned QR also takes: config goes present with the name in hand.
            provision = { eventId, name -> config.save(EventConfig(eventId, name)) },
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
            // minted → provisioned (config present, granted) → listing snapshot total 0 → settled.
            expectState(inSync)
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
            provision = { _, _ -> provisioned = true },
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
        assertEquals(false, provisioned)
    }

    @Test
    fun `a re-join shows the listing-derived snapshot and never a join state`() = runTest {
        // Seed Loading so the transition to the listing snapshot is a real change (equal states conflate).
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 12, total = 47)
            expectState(syncing(up = Arrow.STATIC)) // rising synced count, no join screen
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `empty library maps to In sync`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 1, total = 3))
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 0, total = 0)
            expectState(inSync)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `all present photos synced maps to In sync`() = runTest {
        // Seed a Syncing state so the transition to settled is a real change.
        val source = FakeSyncStatusSource(snapshot(completed = 1, total = 10))
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 34, total = 34)
            expectState(inSync)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `overshoot clamps and maps to In sync`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 1, total = 10))
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 6, total = 5) // synced clamps to 5 >= total
            expectState(inSync)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `a newer snapshot replaces the displayed state entirely`() = runTest {
        val source = FakeSyncStatusSource()
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 1, total = 10)
            expectState(syncing(up = Arrow.STATIC))
            source.value = snapshot(completed = 10, total = 10)
            expectState(inSync)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `initial state derives from source values and never a guess`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34))
        val container = host(source, backgroundScope).container

        assertEquals(inSync, container.stateFlow.value)
    }

    @Test
    fun `denied permission with config present folds into the NeedsAccess status line`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34))
        val permission = FakePermissionSource(PermissionStatus.DENIED)
        val container = host(source, backgroundScope, permission = permission).container

        assertEquals(needsAccess(PermissionStatus.DENIED), container.stateFlow.value)
    }

    @Test
    fun `not-determined permission with config present shows NeedsAccess`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34))
        val permission = FakePermissionSource(PermissionStatus.NOT_DETERMINED)
        val container = host(source, backgroundScope, permission = permission).container

        assertEquals(needsAccess(PermissionStatus.NOT_DETERMINED), container.stateFlow.value)
    }

    @Test
    fun `revoking permission mid-sync switches the status line to NeedsAccess`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34))
        val permission = FakePermissionSource(PermissionStatus.GRANTED)
        host(source, backgroundScope, permission = permission).test(this) {
            runOnCreate()
            permission.permission.value = PermissionStatus.DENIED
            expectState(needsAccess(PermissionStatus.DENIED))
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
    fun `loading snapshot with config and granted permission maps to a joined loading`() = runTest {
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        val permission = FakePermissionSource(PermissionStatus.GRANTED)
        val container = host(source, backgroundScope, permission = permission).container

        assertEquals(joinedLoading, container.stateFlow.value)
    }

    @Test
    fun `permission outranks a loading snapshot when config is present`() = runTest {
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        val permission = FakePermissionSource(PermissionStatus.NOT_DETERMINED)
        val container = host(source, backgroundScope, permission = permission).container

        assertEquals(needsAccess(PermissionStatus.NOT_DETERMINED), container.stateFlow.value)
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
    fun `granting permission reveals the current sync state`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34))
        val permission = FakePermissionSource(PermissionStatus.NOT_DETERMINED)
        host(source, backgroundScope, permission = permission).test(this) {
            runOnCreate()
            permission.permission.value = PermissionStatus.GRANTED
            expectState(inSync)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `a valid deeplink saves config and enters the joined layer`() = runTest {
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        val permission = FakePermissionSource(PermissionStatus.GRANTED)
        val configFake = FakeConfig(null)
        host(source, backgroundScope, permission = permission, configFake = configFake).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeConfigUrl(EventLinkPayload(EVENT_ID)))
            // config now present + granted + Loading snapshot -> joined loading
            expectState(joinedLoading)
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
        assertEquals(encodeConfigUrl(EventLinkPayload(EVENT_ID)), host.inviteUrl.value)
        val decoded = decodeConfigUrl(host.inviteUrl.value!!)
        assertTrue(decoded is ConfigDecodeResult.Success)
        assertEquals(EVENT_ID, decoded.payload.eventId)
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
    fun `event name derives from the persisted config`() = runTest {
        val configFake = FakeConfig(SAMPLE_CONFIG)
        val host = StatusContainerHost(
            FakeSyncStatusSource(), FakePermissionSource(), SpyRequester(), configFake, configFake,
            backgroundScope,
        )
        assertEquals("Anna's Birthday", host.eventName.value)
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

        assertEquals(listOf(encodeConfigUrl(EventLinkPayload(EVENT_ID))), shared)
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
