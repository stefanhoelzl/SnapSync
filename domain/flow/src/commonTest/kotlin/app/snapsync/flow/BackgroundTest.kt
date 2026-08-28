package app.snapsync.flow

import app.snapsync.feature.status.LedgerCounts
import app.snapsync.feature.status.LedgerCountsPoller
import app.snapsync.feature.status.LedgerCountsSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The **background** OS-callback trigger flow: one feature stop and one platform effect, in order.
 *
 * Both halves are load-bearing and neither is visible to a structural gate. Stopping the poll is what
 * keeps a *suspended* app from holding a 2-second timer the OS will not run anyway — the poll's whole
 * premise is that it is foreground-gated (capability `sync-status`), and a flow that ordered the stop
 * but never landed it would leave that premise false with nothing to notice. Scheduling the backstop is
 * what gets staged-but-unimported foreign assets imported when no further download event will wake the
 * app (capability `photo-download`, 5.4) — the wake of last resort, so a `run()` that returned before
 * submitting it would drop the tail silently.
 *
 * `Background` is constructible from `:domain` alone (a poller over a counts source, and one effect
 * lambda), unlike its sibling flows whose controller graphs need the fakes in `:adapter:generic:fake`.
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
    fun `the poll is stopped before the backstop is scheduled`() = runTest {
        val order = mutableListOf<String>()
        val counts = CountingCounts()
        // A poller wrapping a scope we can watch; `stop()` is what the flow is expected to call.
        val poller = LedgerCountsPoller(backgroundScope, counts)
        poller.start()

        Background(statusPoller = poller, scheduleBackstop = { order += "backstop" }).run()

        // The stop is observed by its effect rather than by a spy: the poll must be dead afterwards.
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(0, counts.refreshes, "the poll kept ticking after backgrounding")
        assertEquals(listOf("backstop"), order)
    }

    @Test
    fun `a live poll really stops ticking`() = runTest {
        // The stop has to land, not merely be called: a suspended app cannot act on fresher counts, and
        // the next foreground entry's refresh is the backstop for anything missed meanwhile.
        val counts = CountingCounts()
        val poller = LedgerCountsPoller(backgroundScope, counts)
        poller.start()

        advanceTimeBy(5.seconds)
        runCurrent()
        val whileForegrounded = counts.refreshes
        assertTrue(whileForegrounded > 0, "the poll never ticked, so this test proves nothing")

        Background(statusPoller = poller, scheduleBackstop = {}).run()

        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(whileForegrounded, counts.refreshes, "the poll ticked after the flow stopped it")
    }

    @Test
    fun `run returns only once the backstop submit has finished`() = runTest {
        // Law "A trigger flow never outlives its own run": the shell answers the OS when this returns,
        // so returning while the `BGTaskScheduler` submit is merely queued reports work that had not
        // started.
        val submitted = CompletableDeferred<Unit>()
        var returned = false
        val poller = LedgerCountsPoller(backgroundScope, CountingCounts())

        val run = launch {
            Background(statusPoller = poller, scheduleBackstop = { submitted.await() }).run()
            returned = true
        }
        runCurrent()
        assertFalse(returned, "run() returned while the backstop submit was still in flight")

        submitted.complete(Unit)
        run.join()
        assertTrue(returned)
    }
}
