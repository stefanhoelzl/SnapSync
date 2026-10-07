package app.snapsync.flow

import app.snapsync.feature.status.ForegroundWatches
import app.snapsync.feature.status.LedgerCounts
import app.snapsync.feature.status.LedgerCountsSource
import app.snapsync.feature.status.NetworkWatch
import app.snapsync.feature.status.StatusCountsPoller
import app.snapsync.mock.NetworkMock
import app.snapsync.model.NetworkAccess
import app.snapsync.services.network.NetworkReadings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The **background** OS-callback trigger flow: one feature stop.
 *
 * It is load-bearing and invisible to a structural gate. Stopping the poll is what keeps a *suspended* app from
 * holding a 2-second timer the OS will not run anyway — the poll's whole premise is that it is foreground-gated
 * (capability `sync-status`), and a flow that ordered the stop but never landed it would leave that premise false with
 * nothing to notice.
 *
 * It lives beside its sibling flows' tests, over the mocks: the network watch it stops reads the network through its
 * service, over the network mock's port.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundTest {

    /** Counts ticks: [MutableLedgerCountsSource]'s own `refresh` is inert, so it cannot answer this. */
    private class CountingCounts : LedgerCountsSource {
        var refreshes = 0
        private val _counts = MutableStateFlow(LedgerCounts.UNREAD)
        override val counts: StateFlow<LedgerCounts> = _counts.asStateFlow()
        override suspend fun refresh() {
            refreshes++
        }
    }

    @Test
    fun `a live poll really stops ticking`() = runTest {
        // The stop has to land, not merely be called: a suspended app cannot act on fresher counts, and
        // the next foreground entry's refresh is what catches up on anything missed meanwhile.
        val counts = CountingCounts()
        val poller = StatusCountsPoller(backgroundScope, { counts.refresh() })
        poller.start()

        advanceTimeBy(5.seconds)
        runCurrent()
        val whileForegrounded = counts.refreshes
        assertTrue(whileForegrounded > 0, "the poll never ticked, so this test proves nothing")

        Background(
            ForegroundWatches(poller, NetworkWatch(backgroundScope, NetworkReadings(NetworkMock().port()))),
        ).run()

        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(whileForegrounded, counts.refreshes, "the poll ticked after the flow stopped it")
    }

    @Test
    fun `a shown network notice is withdrawn and the watch follows nothing more`() = runTest {
        // Nothing renders the notice in the background, and a return to the foreground must not show a stale one.
        val network = NetworkMock(NetworkAccess.Offline)
        val watch = NetworkWatch(backgroundScope, NetworkReadings(network.port()))
        watch.start()
        advanceTimeBy(NetworkWatch.DEFAULT_GRACE + 1.seconds)
        assertEquals(
            NetworkAccess.Offline,
            watch.access.value,
            "the watch never published, so this test proves nothing",
        )

        Background(ForegroundWatches(StatusCountsPoller(backgroundScope, {}), watch)).run()

        assertEquals(NetworkAccess.Online(restricted = false), watch.access.value)
        network.operator.access = NetworkAccess.Blocked
        advanceTimeBy(NetworkWatch.DEFAULT_GRACE + 1.seconds)
        assertEquals(
            NetworkAccess.Online(restricted = false),
            watch.access.value,
            "the watch followed the network after the flow stopped it",
        )
    }
}
