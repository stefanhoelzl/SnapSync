package app.snapsync.presentation

import app.snapsync.sync.SyncStatus
import app.snapsync.sync.SyncStatusSource
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.orbitmvi.orbit.test.test

private class FakeSyncStatusSource : SyncStatusSource {
    // replay = 1: emissions are never lost to the subscription race with the
    // container's onCreate collector, while suspending backpressure still
    // delivers every snapshot (no conflation) for deterministic assertions.
    override val status = MutableSharedFlow<SyncStatus>(replay = 1)
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
    active: Boolean = false,
    estimatedRemaining: Duration? = null,
    lastFinishedAt: Instant? = null,
) = SyncStatus(pending, completed, failed, active, estimatedRemaining, lastFinishedAt)

class StatusContainerHostTest {

    @Test
    fun `active pass maps to InProgress with fraction and bucketed estimate`() = runTest {
        val source = FakeSyncStatusSource()
        StatusContainerHost(source, backgroundScope, FakeClock(EPOCH)).test(this) {
            runOnCreate()
            source.status.emit(
                snapshot(pending = 7, completed = 2, failed = 1, active = true, estimatedRemaining = 2.minutes),
            )
            expectState(UiState.InProgress(fraction = 0.3f, estimate = "~2 min left"))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `active pass without estimate shows the estimating placeholder`() = runTest {
        val source = FakeSyncStatusSource()
        StatusContainerHost(source, backgroundScope, FakeClock(EPOCH)).test(this) {
            runOnCreate()
            source.status.emit(snapshot(pending = 7, completed = 3, active = true))
            expectState(UiState.InProgress(fraction = 0.3f, estimate = "estimating…"))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `inactive pass maps to Suspended`() = runTest {
        val source = FakeSyncStatusSource()
        StatusContainerHost(source, backgroundScope, FakeClock(EPOCH)).test(this) {
            runOnCreate()
            source.status.emit(snapshot(pending = 22, completed = 12, active = false))
            expectState(UiState.Suspended)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `finished outcomes map by yield with relative time`() = runTest {
        val clock = FakeClock(EPOCH)
        val source = FakeSyncStatusSource()
        StatusContainerHost(source, backgroundScope, clock).test(this) {
            runOnCreate()
            val fiveMinAgo = clock.now() - 5.minutes
            source.status.emit(snapshot(completed = 34, lastFinishedAt = fiveMinAgo))
            expectState(UiState.Complete("5 min ago"))
            source.status.emit(snapshot(completed = 31, failed = 3, lastFinishedAt = fiveMinAgo))
            expectState(UiState.Incomplete("5 min ago"))
            source.status.emit(snapshot(failed = 34, lastFinishedAt = fiveMinAgo))
            expectState(UiState.Failed("5 min ago"))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `virgin snapshot maps to NeverSynced`() = runTest {
        val source = FakeSyncStatusSource()
        StatusContainerHost(source, backgroundScope, FakeClock(EPOCH)).test(this) {
            runOnCreate()
            source.status.emit(snapshot(completed = 34, lastFinishedAt = EPOCH))
            expectState(UiState.Complete("just now"))
            source.status.emit(snapshot())
            expectState(UiState.NeverSynced)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `relative time ages on tick without a new snapshot`() = runTest {
        val clock = FakeClock(EPOCH)
        val source = FakeSyncStatusSource()
        StatusContainerHost(source, backgroundScope, clock).test(this) {
            runOnCreate()
            source.status.emit(snapshot(completed = 34, lastFinishedAt = clock.now()))
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
        StatusContainerHost(source, backgroundScope, clock).test(this) {
            runOnCreate()
            source.status.emit(snapshot(pending = 7, completed = 3, active = true, estimatedRemaining = 2.minutes))
            expectState(UiState.InProgress(fraction = 0.3f, estimate = "~2 min left"))
            // Ticks fire, but the estimate is rendered verbatim from the snapshot: the very
            // next observed state is the new snapshot's, with nothing in between.
            clock.advance(3.minutes)
            advanceTimeBy(3.minutes)
            source.status.emit(snapshot(completed = 10, lastFinishedAt = clock.now()))
            expectState(UiState.Complete("just now"))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `a newer snapshot replaces the displayed state entirely`() = runTest {
        val source = FakeSyncStatusSource()
        StatusContainerHost(source, backgroundScope, FakeClock(EPOCH)).test(this) {
            runOnCreate()
            source.status.emit(snapshot(pending = 9, completed = 1, active = true))
            expectState(UiState.InProgress(fraction = 0.1f, estimate = "estimating…"))
            source.status.emit(snapshot(pending = 5, completed = 5, active = true))
            expectState(UiState.InProgress(fraction = 0.5f, estimate = "estimating…"))
            cancelAndIgnoreRemainingItems()
        }
    }
}
