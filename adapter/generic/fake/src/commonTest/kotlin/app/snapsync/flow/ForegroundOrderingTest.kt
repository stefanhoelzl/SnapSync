package app.snapsync.flow

import kotlinx.coroutines.async
import kotlin.time.Instant
import app.snapsync.fake.InMemoryAssetPresence
import app.snapsync.fake.InMemoryDownloadStore
import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.membership.LeaveEvent
import app.snapsync.feature.membership.MembershipRefresh
import app.snapsync.feature.status.StatusCountsPoller
import app.snapsync.feature.status.MutableLedgerCountsSource
import app.snapsync.model.CaptureDate
import app.snapsync.model.EventConfig
import app.snapsync.model.JoinLoad
import app.snapsync.model.AssetRef
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.ConfigStore
import app.snapsync.services.backend.EventUnionSource
import app.snapsync.model.ImportResult
import app.snapsync.model.PendingDownload
import app.snapsync.ports.PhotoDownloadJobs
import app.snapsync.model.ImportRequest
import app.snapsync.ports.GalleryImport
import app.snapsync.model.StagedResource
import app.snapsync.model.UnionAsset
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
                eventAlbum = { null },
                myDeviceId = "DEV",
                downloadEnabled = { true },
            ),
            membershipRefresh = MembershipRefresh(
                configSource = configSource,
                store = configStore,
                clock = { Instant.parse("2026-07-09T12:00:00Z") },
                leaveEvent = LeaveEvent(
                    config = configStore,
                    configSource = configSource,
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

    private object NoopJobs : PhotoDownloadJobs {
        override suspend fun enqueue(downloads: List<PendingDownload>) = Unit
        override suspend fun cancelAll() = Unit
    }

    private object NoopImporter : GalleryImport {
        override suspend fun import(request: ImportRequest): ImportResult = ImportResult.Failed("the flow ordering test never imports")
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
