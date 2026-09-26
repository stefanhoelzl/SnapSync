package app.snapsync.flow

import kotlinx.coroutines.async
import app.snapsync.feature.membership.LeaveEvent
import app.snapsync.feature.membership.MembershipRefresh
import app.snapsync.feature.status.StatusCountsPoller
import app.snapsync.feature.status.MutableLedgerCountsSource
import app.snapsync.model.JoinLoad
import app.snapsync.services.backend.EventUnionSource
import app.snapsync.model.UnionAsset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The foreground status refresh is not sequenced behind anything slow** (capability `sync-status`, "Foreground
 * status refresh is not sequenced behind the upload tail").
 *
 * The upload tail — the import drain, the top-up and the walk, whose walk stays outstanding for as long as the app
 * was suspended (774 s, measured on device — `SNAPSYNC-16`) — is not a child of this flow at all any more: the inbound
 * port's implementation requests it after the flow returns (decision record `changes/own-work-per-wake`). What is
 * left to pin is the flow's own fan-out: its children are foreground entry's own work, a slow one holds up none of
 * the others, a throwing one cancels none of them, and `run()` still returns only once every child is done.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundOrderingTest {

    @Test
    fun `a child that never returns does not hold up the status refresh or the poll`() = runTest {
        val settleEntered = CompletableDeferred<Unit>()
        val neverReturns = CompletableDeferred<Unit>()
        var refreshed = false
        var runReturned = false

        // On `backgroundScope`, and read with `runCurrent()` below rather than `advanceUntilIdle()`: the poller is a
        // `while (true) { delay(cadence) }` loop, so advancing virtual time to idle never terminates.
        val counts = MutableLedgerCountsSource()
        val poller = StatusCountsPoller(backgroundScope, { counts.refresh() })

        val flow = foreground(
            statusPoller = poller,
            refreshStatus = { refreshed = true },
            // The stored-upload settle is a backend listing fetch — slow on a bad network, in miniature here.
            settleStoredUploads = {
                settleEntered.complete(Unit)
                neverReturns.await()
            },
        )

        val run = launch {
            flow.run()
            runReturned = true
        }
        runCurrent()

        assertTrue(settleEntered.isCompleted, "the settle must still be invoked — it is a child, not a step removed")
        assertTrue(refreshed, "the status refresh must not wait on it")
        assertFalse(runReturned, "run() must still await every child — the OS is told the truth")

        neverReturns.complete(Unit)
        runCurrent()
        assertTrue(runReturned, "run() returns once its children are done")

        run.cancel()
        poller.stop()
    }

    /**
     * B4: a child that THROWS used to cancel its siblings, because the flow fanned out with a bare `coroutineScope`:
     * the status refresh never ran, and `run()` itself threw at the shell. The `sync-status` spec: a failure in one
     * refresh SHALL NOT cancel its siblings.
     */
    @Test
    fun `a child that throws cancels none of its siblings and run still returns`() = runTest {
        val counts = MutableLedgerCountsSource()
        val poller = StatusCountsPoller(backgroundScope, { counts.refresh() })
        val gate = CompletableDeferred<Unit>()
        var refreshed = false

        val flow = foreground(
            statusPoller = poller,
            // Throws only once its siblings have started, so a cancellation — not a head start — is what the
            // assertion below would catch.
            settleStoredUploads = { gate.await(); throw IllegalStateException("the listing threw") },
            refreshStatus = { gate.await(); refreshed = true },
        )

        val run = async { flow.run() }
        runCurrent()
        gate.complete(Unit)
        run.await() // must not throw

        assertTrue(refreshed, "the status refresh ran to completion despite its sibling's failure")
        poller.stop()
    }

    // ---- scaffolding ----------------------------------------------------------------------------

    private fun CoroutineScope.foreground(
        statusPoller: StatusCountsPoller,
        refreshStatus: suspend () -> Unit,
        settleStoredUploads: suspend () -> Unit = {},
    ): Foreground {
        val config = noMembership()
        return Foreground(
            downloadController = flowDownloadController(EmptyUnion),
            membershipRefresh = MembershipRefresh(
                configSource = config,
                leaveEvent = LeaveEvent(
                    config = config,
                    stopUploads = {},
                    clearLedger = {},
                    notifyLeave = {},
                    scope = this,
                ),
            ),
            statusPoller = statusPoller,
            reloadConfig = {},
            settleStored = settleStoredUploads,
            refreshStatus = refreshStatus,
            // No membership: the reconcile and the membership refresh short-circuit, leaving the settle,
            // the status refresh and the unconditional reclaim as the flow's children — which is exactly
            // the set this test is about.
            activeEventId = { null },
            fetchEventDetails = { JoinLoad.Failed },
            refreshAttestation = {},
        )
    }

    private object EmptyUnion : EventUnionSource {
        override suspend fun union(eventId: String): Result<List<UnionAsset>> = Result.success(emptyList())
    }
}
