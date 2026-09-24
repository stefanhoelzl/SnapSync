package app.snapsync.flow

import app.snapsync.feature.status.LedgerCounts
import app.snapsync.feature.status.LedgerCountsSource
import app.snapsync.feature.status.StatusCountsPoller
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
 * `Background` is constructible from `:domain` alone (a poller over a counts source), unlike its sibling flows whose
 * controller graphs need the fakes in `:adapter:generic:fake`.
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

        Background(statusPoller = poller).run()

        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(whileForegrounded, counts.refreshes, "the poll ticked after the flow stopped it")
    }
}
