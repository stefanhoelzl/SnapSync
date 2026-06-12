package app.snapsync.presentation

import app.snapsync.permission.PermissionRequester
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PermissionStatusSource
import app.snapsync.status.SyncStatus
import app.snapsync.status.SyncStatusSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.orbitmvi.orbit.test.test

// StateFlow fakes mirror the seam contract exactly: the current truth is available
// synchronously at construction, and every assignment is the whole truth.
private class FakeSyncStatusSource(initial: SyncStatus = snapshot()) : SyncStatusSource {
    override val status = MutableStateFlow(initial)
}

private class FakePermissionSource(
    initial: PermissionStatus = PermissionStatus.GRANTED,
) : PermissionStatusSource {
    override val permission = MutableStateFlow(initial)
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
    failed: Int = 0,
    active: Boolean = true,
    estimatedRemaining: Duration? = null,
    lastFinishedAt: Instant? = null,
) = SyncStatus(pending, completed, failed, active, estimatedRemaining, lastFinishedAt)

private fun host(
    source: FakeSyncStatusSource,
    scope: CoroutineScope,
    clock: Clock = FakeClock(EPOCH),
    permission: FakePermissionSource = FakePermissionSource(),
    requester: PermissionRequester = SpyRequester(),
) = StatusContainerHost(source, permission, requester, scope, clock)

class StatusContainerHostTest {

    @Test
    fun `active pass maps to InProgress with fraction and bucketed estimate`() = runTest {
        val source = FakeSyncStatusSource()
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.status.value =
                snapshot(pending = 7, completed = 2, failed = 1, active = true, estimatedRemaining = 2.minutes)
            expectState(UiState.InProgress(fraction = 0.3f, estimate = "~2 min left"))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `active pass without estimate shows the estimating placeholder`() = runTest {
        val source = FakeSyncStatusSource()
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.status.value = snapshot(pending = 7, completed = 3, active = true)
            expectState(UiState.InProgress(fraction = 0.3f, estimate = "estimating…"))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `inactive pass maps to Suspended`() = runTest {
        val source = FakeSyncStatusSource()
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.status.value = snapshot(pending = 22, completed = 12, active = false)
            expectState(UiState.Suspended)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `finished outcomes map by yield with relative time`() = runTest {
        val clock = FakeClock(EPOCH)
        val source = FakeSyncStatusSource()
        host(source, backgroundScope, clock).test(this) {
            runOnCreate()
            val fiveMinAgo = clock.now() - 5.minutes
            source.status.value = snapshot(completed = 34, lastFinishedAt = fiveMinAgo)
            expectState(UiState.Complete("5 min ago"))
            source.status.value = snapshot(completed = 31, failed = 3, lastFinishedAt = fiveMinAgo)
            expectState(UiState.Incomplete("5 min ago"))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `virgin snapshot maps to NeverSynced`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, lastFinishedAt = EPOCH))
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.status.value = snapshot()
            expectState(UiState.NeverSynced)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `relative time ages on tick without a new snapshot`() = runTest {
        val clock = FakeClock(EPOCH)
        val source = FakeSyncStatusSource()
        host(source, backgroundScope, clock).test(this) {
            runOnCreate()
            source.status.value = snapshot(completed = 34, lastFinishedAt = clock.now())
            expectState(UiState.Complete("just now"))
            clock.advance(61.seconds)
            advanceTimeBy(61.seconds)
            expectState(UiState.Complete("1 min ago"))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `estimate is never aged between snapshots`() = runTest {
        val clock = FakeClock(EPOCH)
        val source = FakeSyncStatusSource()
        host(source, backgroundScope, clock).test(this) {
            runOnCreate()
            source.status.value =
                snapshot(pending = 7, completed = 3, active = true, estimatedRemaining = 2.minutes)
            expectState(UiState.InProgress(fraction = 0.3f, estimate = "~2 min left"))
            // Ticks fire, but the estimate is rendered verbatim from the snapshot: the very
            // next observed state is the new snapshot's, with nothing in between.
            clock.advance(3.minutes)
            advanceTimeBy(3.minutes)
            source.status.value = snapshot(completed = 10, lastFinishedAt = clock.now())
            expectState(UiState.Complete("just now"))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `a newer snapshot replaces the displayed state entirely`() = runTest {
        val source = FakeSyncStatusSource()
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.status.value = snapshot(pending = 9, completed = 1, active = true)
            expectState(UiState.InProgress(fraction = 0.1f, estimate = "estimating…"))
            source.status.value = snapshot(pending = 5, completed = 5, active = true)
            expectState(UiState.InProgress(fraction = 0.5f, estimate = "estimating…"))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `initial state derives from source values, never a guess`() = runTest {
        val source = FakeSyncStatusSource(snapshot(failed = 34, lastFinishedAt = EPOCH - 5.minutes))
        val container = host(source, backgroundScope).container

        assertEquals(UiState.Incomplete("5 min ago"), container.stateFlow.value)
    }

    @Test
    fun `permission wins over any sync snapshot`() = runTest {
        val source = FakeSyncStatusSource(snapshot(failed = 34, lastFinishedAt = EPOCH))
        val permission = FakePermissionSource(PermissionStatus.DENIED)
        val container = host(source, backgroundScope, permission = permission).container

        assertEquals(UiState.PermissionDenied, container.stateFlow.value)
    }

    @Test
    fun `granted permission reveals the current sync state`() = runTest {
        val clock = FakeClock(EPOCH)
        val source = FakeSyncStatusSource(snapshot(failed = 34, lastFinishedAt = clock.now() - 5.minutes))
        val permission = FakePermissionSource(PermissionStatus.NOT_DETERMINED)
        host(source, backgroundScope, clock, permission).test(this) {
            runOnCreate()
            permission.permission.value = PermissionStatus.GRANTED
            expectState(UiState.Incomplete("5 min ago"))
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
