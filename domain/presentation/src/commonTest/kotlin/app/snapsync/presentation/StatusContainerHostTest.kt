package app.snapsync.presentation

import app.snapsync.config.ConfigSource
import app.snapsync.config.ConfigStore
import app.snapsync.config.EventConfigPayload
import app.snapsync.config.encodeConfigUrl
import app.snapsync.eventstatus.EventStatus
import app.snapsync.eventstatus.MutableEventStatusSource
import app.snapsync.permission.PermissionRequester
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PermissionStatusSource
import app.snapsync.status.ObservedCompletionsSource
import app.snapsync.status.SyncStatus
import app.snapsync.status.SyncProgress
import app.snapsync.status.SyncStatusSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
}

private class CountingObserved : ObservedCompletionsSource {
    var refreshes = 0
        private set
    private val flow = MutableStateFlow<Set<String>>(emptySet())
    override val keys: StateFlow<Set<String>> = flow
    override suspend fun refresh() {
        refreshes++
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

private class FakeClock(private var current: Instant) : Clock {
    fun advance(duration: Duration) {
        current += duration
    }

    override fun now(): Instant = current
}

private val EPOCH = Instant.fromEpochMilliseconds(0)

private fun snapshot(
    pending: Int = 0,
    completed: Int = 0,
    total: Int = 0,
    failed: Int = 0,
    active: Boolean = true,
    estimatedRemaining: Duration? = null,
    lastFinishedAt: Instant? = null,
) = SyncProgress(pending, completed, total, failed, active, estimatedRemaining, lastFinishedAt)

private fun host(
    source: FakeSyncStatusSource,
    scope: CoroutineScope,
    clock: Clock = FakeClock(EPOCH),
    permission: FakePermissionSource = FakePermissionSource(),
    requester: PermissionRequester = SpyRequester(),
    configFake: FakeConfig = FakeConfig(),
) = StatusContainerHost(source, permission, requester, configFake, configFake, scope, clock)

class StatusContainerHostTest {

    @Test
    fun `fewer synced than present maps to InProgress with the counts and last-sync time`() = runTest {
        val clock = FakeClock(EPOCH)
        val source = FakeSyncStatusSource()
        host(source, backgroundScope, clock).test(this) {
            runOnCreate()
            source.value = snapshot(pending = 35, completed = 12, total = 47, lastFinishedAt = clock.now() - 5.minutes)
            expectState(UiState.InProgress(synced = 12, total = 47, inProgress = 35, finishedAgo = "5 min ago"))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `virgin ledger with photos maps to InProgress zero of N with no last-sync time`() = runTest {
        val source = FakeSyncStatusSource()
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 0, total = 5)
            expectState(UiState.InProgress(synced = 0, total = 5, inProgress = 0, finishedAgo = null))
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
        FakeSyncStatusSource(), permission, SpyRequester(), config, config, scope, FakeClock(EPOCH),
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
    fun `setup gate outranks a join in flight`() = runTest {
        val host = joinHost(EventStatus.Joining, config = FakeConfig(null), scope = backgroundScope)
        assertEquals(UiState.Setup(storageConnected = false, permission = PermissionStatus.GRANTED), host.container.stateFlow.value)
    }

    @Test
    fun `in-progress last-sync time ages on tick`() = runTest {
        val clock = FakeClock(EPOCH)
        val source = FakeSyncStatusSource()
        host(source, backgroundScope, clock).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 1, total = 5, lastFinishedAt = clock.now())
            expectState(UiState.InProgress(synced = 1, total = 5, inProgress = 0, finishedAgo = "just now"))
            clock.advance(61.seconds)
            advanceTimeBy(61.seconds)
            expectState(UiState.InProgress(synced = 1, total = 5, inProgress = 0, finishedAgo = "1 min ago"))
            cancelAndIgnoreRemainingItems()
        }
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
    fun `all present photos synced maps to Completed with relative time`() = runTest {
        val clock = FakeClock(EPOCH)
        val source = FakeSyncStatusSource()
        host(source, backgroundScope, clock).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 34, total = 34, lastFinishedAt = clock.now() - 5.minutes)
            expectState(UiState.Completed(total = 34, finishedAgo = "5 min ago"))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `overshoot clamps the displayed synced count and maps to Completed`() = runTest {
        val clock = FakeClock(EPOCH)
        val source = FakeSyncStatusSource()
        host(source, backgroundScope, clock).test(this) {
            runOnCreate()
            // A deleted photo still COMPLETED in the ledger: completed 6 over a live total of 5.
            source.value = snapshot(completed = 6, total = 5, lastFinishedAt = clock.now())
            expectState(UiState.Completed(total = 5, finishedAgo = "just now"))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `relative time ages on tick without a new snapshot`() = runTest {
        val clock = FakeClock(EPOCH)
        val source = FakeSyncStatusSource()
        host(source, backgroundScope, clock).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 34, total = 34, lastFinishedAt = clock.now())
            expectState(UiState.Completed(total = 34, finishedAgo = "just now"))
            clock.advance(61.seconds)
            advanceTimeBy(61.seconds)
            expectState(UiState.Completed(total = 34, finishedAgo = "1 min ago"))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `a newer snapshot replaces the displayed state entirely`() = runTest {
        val source = FakeSyncStatusSource()
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 1, total = 10)
            expectState(UiState.InProgress(synced = 1, total = 10, inProgress = 0, finishedAgo = null))
            source.value = snapshot(completed = 5, total = 10)
            expectState(UiState.InProgress(synced = 5, total = 10, inProgress = 0, finishedAgo = null))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `initial state derives from source values and never a guess`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34, lastFinishedAt = EPOCH - 5.minutes))
        val container = host(source, backgroundScope).container

        assertEquals(UiState.Completed(total = 34, finishedAgo = "5 min ago"), container.stateFlow.value)
    }

    @Test
    fun `denied permission shows the setup gate over any sync snapshot`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34, lastFinishedAt = EPOCH))
        val permission = FakePermissionSource(PermissionStatus.DENIED)
        val container = host(source, backgroundScope, permission = permission).container

        assertEquals(
            UiState.Setup(storageConnected = true, permission = PermissionStatus.DENIED),
            container.stateFlow.value,
        )
    }

    @Test
    fun `absent config shows the setup gate even when granted`() = runTest {
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        val permission = FakePermissionSource(PermissionStatus.GRANTED)
        val container =
            host(source, backgroundScope, permission = permission, configFake = FakeConfig(null)).container

        assertEquals(
            UiState.Setup(storageConnected = false, permission = PermissionStatus.GRANTED),
            container.stateFlow.value,
        )
    }

    @Test
    fun `loading snapshot with config and granted permission maps to Loading`() = runTest {
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        val permission = FakePermissionSource(PermissionStatus.GRANTED)
        val container = host(source, backgroundScope, permission = permission).container

        assertEquals(UiState.Loading, container.stateFlow.value)
    }

    @Test
    fun `setup gate outranks a loading snapshot`() = runTest {
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        val permission = FakePermissionSource(PermissionStatus.NOT_DETERMINED)
        val container = host(source, backgroundScope, permission = permission).container

        assertEquals(
            UiState.Setup(storageConnected = true, permission = PermissionStatus.NOT_DETERMINED),
            container.stateFlow.value,
        )
    }

    @Test
    fun `both gates satisfied reveals the current sync state`() = runTest {
        val clock = FakeClock(EPOCH)
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34, lastFinishedAt = clock.now() - 5.minutes))
        val permission = FakePermissionSource(PermissionStatus.NOT_DETERMINED)
        host(source, backgroundScope, clock, permission).test(this) {
            runOnCreate()
            permission.permission.value = PermissionStatus.GRANTED
            expectState(UiState.Completed(total = 34, finishedAgo = "5 min ago"))
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
    fun `refresh polls while foreground and pending then stops when drained and resumes on foreground`() = runTest {
        val source = FakeSyncStatusSource(snapshot(pending = 3, total = 5))
        val observed = CountingObserved()
        val foreground = MutableStateFlow(true)
        val containerHost = StatusContainerHost(
            source,
            FakePermissionSource(),
            SpyRequester(),
            FakeConfig(),
            FakeConfig(),
            backgroundScope,
            FakeClock(EPOCH),
            observed,
            foreground,
            pollInterval = 5.seconds,
        )
        containerHost.test(this) {
            runOnCreate()
            runCurrent()
            val afterCreate = observed.refreshes
            assertTrue(afterCreate >= 1, "refreshes immediately when foreground with pending work")

            advanceTimeBy(5.seconds)
            runCurrent()
            assertTrue(observed.refreshes > afterCreate, "keeps polling on the interval")

            // Backgrounded → polling stops.
            foreground.value = false
            runCurrent()
            val afterBackground = observed.refreshes
            advanceTimeBy(20.seconds)
            runCurrent()
            assertEquals(afterBackground, observed.refreshes, "no polling while backgrounded")

            // Foregrounded again → resumes immediately.
            foreground.value = true
            runCurrent()
            assertTrue(observed.refreshes > afterBackground, "resumes on foreground")

            // Drained (pending == 0) → stops even while foreground.
            source.value = snapshot(pending = 0, completed = 5, total = 5)
            runCurrent()
            val afterDrain = observed.refreshes
            advanceTimeBy(20.seconds)
            runCurrent()
            assertEquals(afterDrain, observed.refreshes, "no polling once pending hits zero")

            cancelAndIgnoreRemainingItems()
        }
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
