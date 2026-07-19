package app.snapsync.feature.status

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The foreground-gated status poll (capability `sync-status`; migration step 12 — the ding's
 * replacement). The cadence is the feature's rule, so it is pinned here: ticks arrive once per
 * cadence while started, none before the first cadence elapses (foreground entry already refreshed),
 * none after stop, and repeated starts never stack pollers (the property the old Darwin observer's
 * defensive re-register held — a stacked poller would double the read rate silently).
 */
class LedgerCountsPollerTest {

    private class CountingSource : LedgerCountsSource {
        var refreshes = 0
        override val counts: StateFlow<LedgerCounts> = MutableStateFlow(LedgerCounts.ZERO)
        override suspend fun refresh() {
            refreshes++
        }
    }

    @Test
    fun `polls once per cadence while started`() = runTest {
        val source = CountingSource()
        val poller = LedgerCountsPoller(backgroundScope, source, cadence = 2.seconds)

        poller.start()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(0, source.refreshes, "the first tick waits one full cadence")
        advanceTimeBy(1.seconds)
        runCurrent()
        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals(4, source.refreshes, "one refresh per elapsed cadence")
        poller.stop()
    }

    @Test
    fun `stop ends the poll`() = runTest {
        val source = CountingSource()
        val poller = LedgerCountsPoller(backgroundScope, source, cadence = 2.seconds)

        poller.start()
        advanceTimeBy(4.seconds)
        runCurrent()
        poller.stop()
        val atStop = source.refreshes
        advanceTimeBy(20.seconds)
        runCurrent()
        assertEquals(atStop, source.refreshes, "no ticks after stop")
    }

    @Test
    fun `repeated starts never stack pollers`() = runTest {
        val source = CountingSource()
        val poller = LedgerCountsPoller(backgroundScope, source, cadence = 2.seconds)

        poller.start()
        poller.start() // a second foreground entry while already foregrounded
        poller.start()
        advanceTimeBy(4.seconds)
        runCurrent()
        assertEquals(2, source.refreshes, "one poll loop regardless of repeated starts")
        poller.stop()
    }

    @Test
    fun `start after stop polls again`() = runTest {
        val source = CountingSource()
        val poller = LedgerCountsPoller(backgroundScope, source, cadence = 2.seconds)

        poller.start()
        advanceTimeBy(2.seconds)
        runCurrent()
        poller.stop()
        poller.start()
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(2, source.refreshes, "a fresh foreground entry restarts the poll")
        poller.stop()
    }

    @Test
    fun `stop before any start is a no-op`() = runTest {
        val poller = LedgerCountsPoller(backgroundScope, CountingSource(), cadence = 2.seconds)
        poller.stop() // backgrounding before the first foreground entry must not throw
    }

    @Test
    fun `a throwing refresh does not kill the loop`() = runTest {
        // The containment contract: a poll that dies silently on one bad tick would freeze the
        // screen for the rest of the foreground session — the invisible failure the poll exists
        // to prevent. The loop swallows the throw and the next tick retries at cadence.
        val source = object : LedgerCountsSource {
            var refreshes = 0
            override val counts: StateFlow<LedgerCounts> = MutableStateFlow(LedgerCounts.ZERO)
            override suspend fun refresh() {
                refreshes++
                if (refreshes == 1) error("one bad tick")
            }
        }
        val poller = LedgerCountsPoller(backgroundScope, source, cadence = 2.seconds)

        poller.start()
        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals(3, source.refreshes, "the loop survives the throwing first tick and keeps polling")
        poller.stop()
    }
}
