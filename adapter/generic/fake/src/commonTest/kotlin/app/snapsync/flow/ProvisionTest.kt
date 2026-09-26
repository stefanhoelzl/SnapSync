package app.snapsync.flow

import app.snapsync.model.CaptureCutoff
import app.snapsync.model.SelectionCalibration
import app.snapsync.fake.InMemoryAlbumMapStore
import app.snapsync.fake.InMemoryAssetPresence
import app.snapsync.fake.InMemoryDownloadStore
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.download.DownloadController
import app.snapsync.model.EventConfig
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.ports.AlbumManager
import app.snapsync.model.AssetRef
import app.snapsync.ports.EventUnionSource
import app.snapsync.model.ImportResult
import app.snapsync.model.PendingDownload
import app.snapsync.ports.PhotoDownloadJobs
import app.snapsync.model.ImportRequest
import app.snapsync.ports.GalleryImport
import app.snapsync.model.StagedResource
import app.snapsync.ports.UnionAsset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The **provision** trigger flow — the shared path for a scanned/typed event link and a freshly created
 * event. It coordinates seven steps in a fixed order and decides nothing; what each step *means* is its
 * own feature's rule. What this test pins is the coordination, which no gate can see.
 *
 * The order is not arbitrary, and four of the edges are load-bearing:
 *
 * - **The leave precedes the save.** On a switch, the previous event is left on the backend while the
 *   config still names it; a save first would overwrite the only record of which event to leave.
 * - **The share-set load precedes the save, and runs only on a transition.** A switch or a first join
 *   replaces the upload ledger before any cycle can see the new membership; a re-provision of the joined
 *   event must not, because that would reset a live membership's work in flight.
 * - **The save precedes everything downstream.** The status refresh, the arm and the reconcile all read
 *   the persisted membership — including the extension, in its own process.
 * - **The album call is unconditional and carries the access fact.** `ensureAlbum` owns the
 *   granted/opt-in gate as its own leading guard (capability `event-album`), so no caller can forget
 *   it; this flow's job is only to pass `hasUsableAccess()` through honestly.
 *
 * A provision into a new membership now DOES reach destructive verbs — a switch stops the previous
 * membership's uploads, and the load resets the ledger (capability `background-upload`, reversed by
 * `changes/join-loads-leave-clears`) — so a Stay's "nothing was stopped or loaded" is asserted, not assumed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProvisionTest {

    private val eventA = "11111111-1111-4111-8111-111111111111"
    private val eventB = "22222222-2222-4222-8222-222222222222"

    private fun config(eventId: String, saveToAlbum: Boolean = false) = EventConfig(
        eventId = eventId,
        name = "Anna's Birthday",
        minPhotoDate = captureCutoff("2026-07-14T18:00:00Z"),
        maxPhotoDate = captureCeiling("2026-07-21T18:00:00Z"),
        saveToAlbum = saveToAlbum,
    )

    @Test
    fun `a switch leaves the previous event first and then runs the join steps in order`() = runTest {
        val order = mutableListOf<String>()
        val flow = provision(
            order = order,
            activeEventId = { eventA },
            saveToAlbum = true,
        )

        flow.run(config(eventB, saveToAlbum = true))

        // The leave carries the PREVIOUS id and happens before the save that overwrites it; the new share
        // set is loaded before the save too.
        assertEquals("leave:$eventA", order.first())
        assertEquals(
            listOf("leave:$eventA", "load", "save:$eventB", "arm", "refresh", "album"),
            order.take(6),
        )
        // Step 7 is concurrent, so membership is asserted rather than order — but both are awaited.
        assertTrue("reconcile:$eventB" in order, "the foreign-download reconcile never ran")
        assertTrue("push" in order, "the push token was never re-registered")
    }

    @Test
    fun `re-scanning the already-joined event is a Stay so nothing is left or loaded`() = runTest {
        // `switchDecision` is membership's sealed rule; this flow must not invent a leave for it — nor a
        // load, which would reset the live membership's upload ledger with nothing stopped.
        val order = mutableListOf<String>()
        provision(order = order, activeEventId = { eventA }).run(config(eventA))

        assertTrue(order.none { it.startsWith("leave:") }, "a re-scan left its own event: $order")
        assertTrue("load" !in order, "a re-scan reset the live membership's ledger: $order")
        assertEquals("save:$eventA", order.first())
        assertTrue("arm" !in order, "a re-scan must not reach the upload arm — its registration would wipe jobs: $order")
    }

    @Test
    fun `a first join leaves nothing and loads the share set before the save`() = runTest {
        val order = mutableListOf<String>()
        provision(order = order, activeEventId = { null }).run(config(eventA))

        assertTrue(order.none { it.startsWith("leave:") }, "a first join fired a leave: $order")
        assertEquals(listOf("load", "save:$eventA"), order.take(2))
    }

    @Test
    fun `the whole config is persisted as-is and never destructured`() = runTest {
        // A newly-added field must not be dropped before the persist the extension reads, so the flow
        // hands the config object straight through.
        var saved: EventConfig? = null
        val cfg = config(eventB, saveToAlbum = true)
        provision(order = mutableListOf(), saveConfig = { saved = it }).run(cfg)

        assertEquals(cfg, saved)
    }

    @Test
    fun `the album call carries the access fact rather than a caller's guess`() = runTest {
        // Same membership, same opt-in — only the grant differs, and the coordinator's own leading
        // guard is what turns that into "no album". The flow's job is to pass it through honestly.
        val albums = InMemoryAlbumMapStore()
        provision(order = mutableListOf(), hasUsableAccess = { false }, albumStore = albums, saveToAlbum = true)
            .run(config(eventB, saveToAlbum = true))
        assertNull(albums.get(eventB), "an album was created for a membership with no photo access")

        val granted = InMemoryAlbumMapStore()
        provision(order = mutableListOf(), hasUsableAccess = { true }, albumStore = granted, saveToAlbum = true)
            .run(config(eventB, saveToAlbum = true))
        assertEquals("album-for-Anna's Birthday", granted.get(eventB))
    }

    @Test
    fun `run returns only once both concurrent children have finished`() = runTest {
        // Law "A trigger flow never outlives its own run": a join whose reconcile and registration are
        // merely queued when `run()` returns is a join the caller cannot truthfully report as finished.
        val release = CompletableDeferred<Unit>()
        var returned = false
        val flow = provision(order = mutableListOf(), registerPush = { release.await() })

        val run = launch {
            flow.run(config(eventB))
            returned = true
        }
        runCurrent()
        assertFalse(returned, "run() returned while the push registration was still in flight")

        release.complete(Unit)
        run.join()
        assertTrue(returned)
    }

    /** B4, on the join: a failed push registration cancels nothing — the reconcile still runs, `run()` still returns. */
    @Test
    fun `a failing push registration cancels none of its siblings`() = runTest {
        val order = mutableListOf<String>()
        val flow = provision(order = order, registerPush = { throw IllegalStateException("keychain locked") })

        flow.run(config(eventB)) // must not throw

        assertTrue("reconcile:$eventB" in order, "the download reconcile ran beside the failed registration")
    }

    // ---- scaffolding ----------------------------------------------------------------------------

    private fun provision(
        order: MutableList<String>,
        activeEventId: () -> String? = { null },
        saveConfig: suspend (EventConfig) -> Unit = { order += "save:${it.eventId}" },
        hasUsableAccess: () -> Boolean = { true },
        albumStore: InMemoryAlbumMapStore = InMemoryAlbumMapStore(),
        saveToAlbum: Boolean = false,
        registerPush: suspend () -> Unit = { order += "push" },
    ): Provision {
        return Provision(
            downloadController = DownloadController(
                union = RecordingUnion(order),
                store = InMemoryDownloadStore(),
                jobs = NoopJobs,
                importer = NoopImporter,
                presence = InMemoryAssetPresence(),
                eventAlbum = { null },
                myDeviceId = "DEV",
                downloadEnabled = { true },
            ),
            albumCoordinator = AlbumCoordinator(RecordingAlbums(order, saveToAlbum), albumStore),
            activeEventId = activeEventId,
            // The entry's inner order (stop, leave, load, save, start uploads) is `MembershipEntryTest`'s; here it
            // is recorded in that order so the flow's placement of it is visible.
            enterMembership = { previous, cfg ->
                previous?.let { order += "leave:$it" }
                order += "load"
                saveConfig(cfg)
                order += "arm"
            },
            saveConfig = saveConfig,
            refreshStatus = { order += "refresh" },
            hasUsableAccess = hasUsableAccess,
            registerPush = registerPush,
        )
    }

    private class RecordingUnion(private val order: MutableList<String>) : EventUnionSource {
        override suspend fun union(eventId: String): Result<List<UnionAsset>> {
            order += "reconcile:$eventId"
            return Result.success(emptyList())
        }
    }

    /** Records the album step; only reached when the coordinator's own granted/opt-in guard passes. */
    private class RecordingAlbums(
        private val order: MutableList<String>,
        private val recordCreate: Boolean,
    ) : AlbumManager {
        override suspend fun ensureCreated(name: String): String? {
            if (recordCreate) order += "album"
            return "album-for-$name"
        }
        override suspend fun exists(albumLocalId: String): Boolean = true
        override suspend fun add(albumLocalId: String, assetIds: List<String>) = Unit
        override suspend fun assetIdsInAlbums(calibration: SelectionCalibration, since: CaptureCutoff): Set<String> = emptySet()
    }

    private object NoopJobs : PhotoDownloadJobs {
        override suspend fun enqueue(downloads: List<PendingDownload>) = Unit
        override suspend fun cancelAll() = Unit
    }

    private object NoopImporter : GalleryImport {
        override suspend fun import(request: ImportRequest): ImportResult = ImportResult.Failed("the provision test never imports")
    }
}
