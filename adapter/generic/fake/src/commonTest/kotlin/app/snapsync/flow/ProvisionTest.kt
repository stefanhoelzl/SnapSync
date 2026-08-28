package app.snapsync.flow

import app.snapsync.fake.InMemoryAlbumMapStore
import app.snapsync.fake.InMemoryAssetPresence
import app.snapsync.fake.InMemoryDownloadStore
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.upload.UploadArm
import app.snapsync.feature.upload.UploadMechanismRuntime
import app.snapsync.model.EventConfig
import app.snapsync.model.UploadMechanism
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.ports.AlbumManager
import app.snapsync.ports.AssetRef
import app.snapsync.ports.EventUnionSource
import app.snapsync.ports.ImportResult
import app.snapsync.ports.PendingDownload
import app.snapsync.ports.PhotoDownloadJobs
import app.snapsync.ports.PhotoLibraryImporter
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The **provision** trigger flow — the shared path for a scanned/typed event link and a freshly created
 * event. It coordinates six steps in a fixed order and decides nothing; what each step *means* is its
 * own feature's rule. What this test pins is the coordination, which no gate can see.
 *
 * The order is not arbitrary, and three of the edges are load-bearing:
 *
 * - **The leave precedes the save.** On a switch, the previous event is left on the backend while the
 *   config still names it; a save first would overwrite the only record of which event to leave.
 * - **The save precedes everything downstream.** The status refresh, the arm and the reconcile all read
 *   the persisted membership — including the extension, in its own process.
 * - **The album call is unconditional and carries the access fact.** `ensureAlbum` owns the
 *   granted/opt-in gate as its own leading guard (capability `event-album`), so no caller can forget
 *   it; this flow's job is only to pass `isGranted()` through honestly.
 *
 * The destructive verbs a provision must never reach do not exist in the seams it calls
 * (`upload-lifecycle`), which is why there is no "and nothing was cleared" assertion here — the
 * absence is structural, not behavioural.
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

        // The leave carries the PREVIOUS id and happens before the save that overwrites it.
        assertEquals("leave:$eventA", order.first())
        assertEquals(
            listOf("leave:$eventA", "save:$eventB", "refresh", "arm", "album"),
            order.take(5),
        )
        // Step 6 is concurrent, so membership is asserted rather than order — but both are awaited.
        assertTrue("reconcile:$eventB" in order, "the foreign-download reconcile never ran")
        assertTrue("push" in order, "the push token was never re-registered")
    }

    @Test
    fun `re-scanning the already-joined event is a Stay so nothing is left`() = runTest {
        // `switchDecision` is membership's sealed rule; this flow must not invent a leave for it.
        val order = mutableListOf<String>()
        provision(order = order, activeEventId = { eventA }).run(config(eventA))

        assertTrue(order.none { it.startsWith("leave:") }, "a re-scan left its own event: $order")
        assertEquals("save:$eventA", order.first())
    }

    @Test
    fun `a first join with no previous membership leaves nothing`() = runTest {
        val order = mutableListOf<String>()
        provision(order = order, activeEventId = { null }).run(config(eventA))

        assertTrue(order.none { it.startsWith("leave:") }, "a first join fired a leave: $order")
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
        provision(order = mutableListOf(), isGranted = { false }, albumStore = albums, saveToAlbum = true)
            .run(config(eventB, saveToAlbum = true))
        assertNull(albums.get(eventB), "an album was created for a membership with no photo access")

        val granted = InMemoryAlbumMapStore()
        provision(order = mutableListOf(), isGranted = { true }, albumStore = granted, saveToAlbum = true)
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

    // ---- scaffolding ----------------------------------------------------------------------------

    private fun provision(
        order: MutableList<String>,
        activeEventId: () -> String? = { null },
        saveConfig: suspend (EventConfig) -> Unit = { order += "save:${it.eventId}" },
        isGranted: () -> Boolean = { true },
        albumStore: InMemoryAlbumMapStore = InMemoryAlbumMapStore(),
        saveToAlbum: Boolean = false,
        registerPush: suspend () -> Unit = { order += "push" },
    ): Provision {
        val mechanism = RecordingMechanism(order)
        return Provision(
            uploadArm = UploadArm(
                resolve = { UploadMechanism.URL_SESSION },
                mechanismFor = { mechanism },
                membershipIncludesUpload = { true },
            ),
            downloadController = DownloadController(
                union = RecordingUnion(order),
                store = InMemoryDownloadStore(),
                jobs = NoopJobs,
                importer = NoopImporter,
                presence = InMemoryAssetPresence(),
                myDeviceId = "DEV",
                downloadEnabled = { true },
            ),
            albumCoordinator = AlbumCoordinator(RecordingAlbums(order, saveToAlbum), albumStore),
            activeEventId = activeEventId,
            notifyLeave = { order += "leave:$it" },
            saveConfig = saveConfig,
            refreshStatus = { order += "refresh" },
            isGranted = isGranted,
            registerPush = registerPush,
        )
    }

    private class RecordingMechanism(private val order: MutableList<String>) : UploadMechanismRuntime {
        override suspend fun start() { order += "arm" }
        override suspend fun stop() { order += "arm-stop" }
        override suspend fun onForeground() = Unit
        override suspend fun onSilentPush(eventId: String) = Unit
        override suspend fun onBackgroundTask() = Unit
        override suspend fun onSelectionChanged() = Unit
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
        override suspend fun add(albumLocalId: String, rawLocalIds: List<String>) = Unit
        override suspend fun assetIdsInAlbums(titles: Set<String>, since: String): Set<String> = emptySet()
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
        ): ImportResult = ImportResult.Failed("the provision test never imports")
    }
}
