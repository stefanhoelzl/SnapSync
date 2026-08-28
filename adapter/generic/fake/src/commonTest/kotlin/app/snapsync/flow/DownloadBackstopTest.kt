package app.snapsync.flow

import app.snapsync.fake.InMemoryAssetPresence
import app.snapsync.fake.InMemoryDownloadStore
import app.snapsync.feature.download.DownloadController
import app.snapsync.ports.AssetRef
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.ImportResult
import app.snapsync.ports.ImportableAsset
import app.snapsync.ports.PendingDownload
import app.snapsync.ports.PhotoDownloadJobs
import app.snapsync.ports.PhotoLibraryImporter
import app.snapsync.ports.EventUnionSource
import app.snapsync.ports.StagedResource
import app.snapsync.ports.UnionAsset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The **download import-tail backstop** trigger flow (capability `photo-download`, 5.4): the wake that
 * drains staged-but-not-yet-imported foreign assets when no further download event would wake the app —
 * the last transfer having overrun its `URLSession` wake budget, say.
 *
 * Three properties, none of them visible to a structural gate:
 *
 * **Order.** The config re-read comes first because the import's own guards read that config StateFlow,
 * and a background launch before the first unlock seeds an unreadable — therefore empty — one. The
 * attestation refresh comes next: this `BGTask` is the one recurring wake that does not depend on an
 * upload having succeeded, which matters precisely because an expired token is what stops uploads
 * succeeding.
 *
 * **`run()` awaits the drain.** The caller answers a `BGTask` when this returns (law "A trigger flow
 * never outlives its own run"), so returning while the drain is merely queued reports work that had not
 * started. The flow's own comment says it is awaited and not launched; that is what the second test
 * checks.
 *
 * **A failing drain is contained.** The flow swallows it, and it must: an escaping error here fails the
 * OS task, and a `BGProcessingTask` the system may already defer indefinitely is not one to spend on a
 * crash. Nothing else imports this tail, so the next wake retrying is the whole recovery.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadBackstopTest {

    @Test
    fun `the config re-read and the token refresh both precede the import drain`() = runTest {
        val order = mutableListOf<String>()
        val store = DrainSpyStore(onDrain = { order += "drain" })

        backstop(
            store = store,
            reloadConfig = { order += "reload" },
            refreshAttestation = { order += "attest" },
        ).run()

        assertEquals(listOf("reload", "attest", "drain"), order)
    }

    @Test
    fun `run returns only after the drain finishes`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var returned = false
        val store = DrainSpyStore(onDrain = {}, gate = { entered.complete(Unit); release.await() })

        val run = launch {
            backstop(store).run()
            returned = true
        }
        runCurrent()

        assertTrue(entered.isCompleted, "the drain was never reached")
        assertFalse(returned, "run() returned while the drain was still in flight — the BGTask is lied to")

        release.complete(Unit)
        run.join()
        assertTrue(returned)
    }

    @Test
    fun `a failing drain is contained so the OS task still completes`() = runTest {
        // Not defensive tidiness: an escaping error fails a `BGProcessingTask` the OS may already be
        // deferring indefinitely, and nothing else imports this tail. Retrying at the next wake is the
        // recovery, and it only happens if this one ends normally.
        var attested = false
        val store = DrainSpyStore(onDrain = { error("the store blew up") })

        backstop(store, refreshAttestation = { attested = true }).run() // must not throw

        assertTrue(attested, "the earlier steps still ran")
    }

    // ---- scaffolding ----------------------------------------------------------------------------

    private fun backstop(
        store: DownloadStore,
        reloadConfig: suspend () -> Unit = {},
        refreshAttestation: suspend () -> Unit = {},
    ) = DownloadBackstop(
        downloadController = DownloadController(
            union = EmptyUnion,
            store = store,
            jobs = NoopJobs,
            importer = NoopImporter,
            presence = InMemoryAssetPresence(),
            myDeviceId = "DEV",
            downloadEnabled = { true },
        ),
        reloadConfig = reloadConfig,
        refreshAttestation = refreshAttestation,
    )

    /**
     * Wraps the honest [InMemoryDownloadStore] to observe the drain reaching it — `importableAssets()`
     * is the drain's first act, so it is where the pass becomes observable, blockable and failable. A
     * wrapper rather than a subclass because the fake is final by the honesty gate.
     */
    private class DrainSpyStore(
        private val onDrain: () -> Unit,
        private val gate: suspend () -> Unit = {},
        private val inner: InMemoryDownloadStore = InMemoryDownloadStore(),
    ) : DownloadStore by inner {
        override suspend fun importableAssets(): List<ImportableAsset> {
            onDrain()
            gate()
            return inner.importableAssets()
        }
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
        ): ImportResult = ImportResult.Failed("the backstop test never imports")
    }
}
