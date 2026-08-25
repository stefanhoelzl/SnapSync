package app.snapsync.flow

import app.snapsync.fake.InMemoryAssetPresence
import app.snapsync.fake.InMemoryDownloadStore
import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.membership.LeaveEvent
import app.snapsync.feature.membership.MembershipRefresh
import app.snapsync.feature.status.LedgerCountsPoller
import app.snapsync.feature.status.MutableLedgerCountsSource
import app.snapsync.model.CaptureDate
import app.snapsync.model.EventConfig
import app.snapsync.model.JoinLoad
import app.snapsync.ports.AssetRef
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.ConfigStore
import app.snapsync.ports.EventUnionSource
import app.snapsync.ports.ImportResult
import app.snapsync.ports.PendingDownload
import app.snapsync.ports.PhotoDownloadJobs
import app.snapsync.ports.PhotoLibraryImporter
import app.snapsync.ports.StagedResource
import app.snapsync.ports.UnionAsset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The foreground status refresh is not sequenced behind the upload pump** (capability `sync-status`).
 *
 * The flow transcriber pins the *shape* — `architecture/flows/Foreground.md` now shows
 * `pumpForeground()` inside the `par concurrent` block, and a stale diagram fails the build — but it
 * cannot see whether the refresh actually *runs* while the pump is stuck. That is the property members
 * felt: the app-driven pump awaits a whole upload cycle, and a cycle's discovery walk stays outstanding
 * for as long as the app was suspended (774 s, measured on device — `SNAPSYNC-16`). While the pump was
 * awaited *before* the fan-out, a visit shorter than that unwinding reached none of the work below it,
 * so no count was ever read and the joined screen answered from its seeds.
 *
 * The test therefore blocks the pump forever and asserts the rest of the flow still happens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundOrderingTest {

    @Test
    fun `a pump that never returns does not hold up the status refresh or the poll`() = runTest {
        val pumpEntered = CompletableDeferred<Unit>()
        val neverReturns = CompletableDeferred<Unit>()
        var refreshed = false
        var runReturned = false

        // On `backgroundScope`, and read with `runCurrent()` below rather than `advanceUntilIdle()`:
        // the poller is a `while (true) { delay(cadence) }` loop, so advancing virtual time to idle
        // never terminates. `runCurrent()` drains what is pending without moving the clock, which is
        // precisely the question here — what ran WITHOUT waiting for anything.
        val counts = MutableLedgerCountsSource()
        val poller = LedgerCountsPoller(backgroundScope, counts)

        val flow = foreground(
            statusPoller = poller,
            pumpForeground = {
                pumpEntered.complete(Unit)
                neverReturns.await() // the suspended-walk cycle, in miniature
            },
            refreshStatus = { refreshed = true },
        )

        val run = launch {
            flow.run()
            runReturned = true
        }
        runCurrent()

        assertTrue(pumpEntered.isCompleted, "the pump must still be invoked — it is a child, not a step removed")
        assertTrue(refreshed, "the status refresh must not wait on the pump")
        assertFalse(runReturned, "run() must still await every child — the OS is told the truth")

        // Releasing the pump lets the flow finish, confirming it really was awaiting it all along.
        neverReturns.complete(Unit)
        runCurrent()
        assertTrue(runReturned, "run() returns once its children — the pump included — are done")

        run.cancel()
        poller.stop()
    }

    // ---- scaffolding ----------------------------------------------------------------------------

    private fun CoroutineScope.foreground(
        statusPoller: LedgerCountsPoller,
        pumpForeground: suspend () -> Unit,
        refreshStatus: suspend () -> Unit,
        onReclaim: () -> Unit = {},
    ): Foreground {
        val configSource = FakeConfigSource()
        val configStore = FakeConfigStore()
        return Foreground(
            downloadController = DownloadController(
                union = EmptyUnion,
                store = ReclaimSpyStore(onReclaim),
                jobs = NoopJobs,
                importer = NoopImporter,
                presence = InMemoryAssetPresence(),
                myDeviceId = "DEV",
                downloadEnabled = { true },
            ),
            membershipRefresh = MembershipRefresh(
                configSource = configSource,
                store = configStore,
                now = { CaptureDate("2026-07-09T12:00:00Z") },
                leaveEvent = LeaveEvent(
                    config = configStore,
                    configSource = configSource,
                    stopUploads = {},
                    notifyLeave = {},
                    scope = this,
                ),
            ),
            statusPoller = statusPoller,
            reloadConfig = {},
            pumpForeground = pumpForeground,
            refreshStatus = refreshStatus,
            // No membership: the reconcile and the membership refresh short-circuit, leaving the pump,
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

    private object NoopJobs : PhotoDownloadJobs {
        override suspend fun enqueue(downloads: List<PendingDownload>) = Unit
        override suspend fun cancelAll() = Unit
    }

    private object NoopImporter : PhotoLibraryImporter {
        override suspend fun import(
            ref: AssetRef,
            resources: List<StagedResource>,
            creationDate: String,
        ): ImportResult = ImportResult.Failed("the flow ordering test never imports")
    }

    /**
     * Wraps the honest [InMemoryDownloadStore] to record the reclaim pass reaching it. A wrapper rather
     * than a subclass because the fake is final by the honesty gate — its surface is the port contract
     * plus its constructor, and operator rigging is the caller's business.
     */
    private class ReclaimSpyStore(
        private val onReclaim: () -> Unit,
        private val inner: InMemoryDownloadStore = InMemoryDownloadStore(),
    ) : DownloadStore by inner {
        override suspend fun stagedPathsOfImportedAssets(): List<String> {
            onReclaim()
            return inner.stagedPathsOfImportedAssets()
        }
    }

    private class FakeConfigSource : ConfigSource {
        override val config: StateFlow<EventConfig?> = MutableStateFlow(null)
    }

    private class FakeConfigStore : ConfigStore {
        override suspend fun save(config: EventConfig) = Unit
        override suspend fun clear() = Unit
    }
}
