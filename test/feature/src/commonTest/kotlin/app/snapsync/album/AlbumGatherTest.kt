@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.album

import app.snapsync.feature.support.LEDGER_EVENT
import app.snapsync.model.EntryScope
import app.snapsync.model.AssetId
import app.snapsync.model.GalleryAccess
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.mock.inMemoryGallery
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.feature.support.configService
import app.snapsync.feature.support.galleryAccess
import app.snapsync.model.AlbumId
import app.snapsync.model.AlbumKind
import app.snapsync.model.AlbumRecord
import app.snapsync.model.GalleryRead
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.GalleryReader
import app.snapsync.services.album.AlbumMapService
import app.snapsync.services.ledger.LedgerService
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.album.AlbumGather
import app.snapsync.model.EventConfig
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.selectionPolicyFor
import app.snapsync.services.gallery.GalleryAlbums
import app.snapsync.model.AssetRef
import app.snapsync.services.config.ConfigService
import app.snapsync.services.downloads.DownloadService
import app.snapsync.model.PlannedAsset
import app.snapsync.model.PlannedResource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The event album's gather (capability `event-album`, "Ensuring the album gathers what the device already
 * holds"), over the honest in-memory ledger and download store: which photos a gather places, and which it
 * must not.
 */
class AlbumGatherTest {

    /** The photo library's album surface at the port, recording every add; [failingCall]'s add throws. */
    private class RecordingAlbumManager(
        private val failingCall: Int? = null,
    ) : GalleryReader by inMemoryGallery(MutableStateFlow(emptyList())) {
        val calls = mutableListOf<List<AssetId>>()
        /** When set, every add waits for it — a gather holding its lock. */
        var gate: CompletableDeferred<Unit>? = null
        val added: Set<AssetId> get() = calls.flatten().toSet()
        override suspend fun createAlbum(title: String): AlbumId? = error("the gather never creates an album")
        override suspend fun albumsById(ids: Set<AlbumId>): GalleryRead<List<AlbumRecord>> =
            GalleryRead.Read(ids.map { AlbumRecord(it, "Trip") })
        override suspend fun addToAlbum(album: AlbumId, assets: Set<AssetId>): WriteOutcome {
            gate?.await()
            calls += assets.toList()
            if (calls.size == failingCall) error("boom")
            return WriteOutcome.Ok
        }
    }

    private fun config(
        eventId: String = "E2",
        saveToAlbum: Boolean = true,
        from: String = "2026-09-01T00:00:00Z",
    ) = EventConfig(
        eventId = eventId,
        name = "Trip",
        minPhotoDate = captureCutoff(from),
        maxPhotoDate = captureCeiling("2026-09-30T00:00:00Z"),
        saveToAlbum = saveToAlbum,
    )

    private class Rig(
        cfg: EventConfig?,
        val manager: RecordingAlbumManager,
        granted: Boolean,
        scope: CoroutineScope,
        kind: AlbumKind = AlbumKind.COLLECTION,
    ) {
        val config: ConfigService = configService(cfg)
        private val databases = inMemoryDatabases()
        val ledger = LedgerService(databases) { LEDGER_EVENT }
        val downloads = DownloadService(databases)
        val grant = MutableStateFlow(if (granted) GalleryAccess.GRANTED else GalleryAccess.DENIED)
        val gather = AlbumGather(
            configSource = config,
            ledger = ledger,
            policyFor = { c -> selectionPolicyFor(c, suppressedAssetIds = { emptySet() }, albumExcludedAssetIds = { emptySet() }) },
            downloads = downloads,
            photoAccess = galleryAccess(grant),
            coordinator = AlbumCoordinator(
                GalleryAlbums(manager),
                AlbumMapService(inMemoryPreferences(), inMemorySecureStore()).apply { put("E2", "ALBUM-2") },
                kind = kind,
            ),
            scope = scope,
            entryContext = EntryScope.None,
            batchSize = 2,
        )

        suspend fun own(assetId: String, creationDate: String, eventId: String = "E1") {
            ledger.recordUnlessSettled(
                LedgerEntry("$assetId.HEIC", AssetId(assetId), LedgerState.COMPLETED, creationDate = creationDate),
            )
        }

        /** A received photo, imported; [eventId] is the event whose reconcile tagged it, `null` for none. */
        suspend fun imported(device: String, assetId: String, localId: String, eventId: String? = "E2") {
            val ref = AssetRef(device, AssetId(assetId))
            val resources = listOf(PlannedResource("$assetId.heic", "u", "primary", "image/heic", "a.heic"))
            downloads.planAll(listOf(PlannedAsset(ref, "2026-09-10T00:00:00Z", resources)), eventId, members = listOf(ref))
            downloads.markImported(ref, AssetId(localId))
        }
    }

    private fun CoroutineScope.rig(
        cfg: EventConfig? = config(),
        failingCall: Int? = null,
        granted: Boolean = true,
        kind: AlbumKind = AlbumKind.COLLECTION,
    ) = Rig(cfg, RecordingAlbumManager(failingCall), granted, this, kind)

    @Test
    fun `a photo carried over from an earlier event is gathered when this window admits it`() = runTest {
        val r = rig()
        r.own("OWN_1_L0_001", "2026-09-10T00:00:00Z", eventId = "E1")
        r.gather.gather("E2")
        assertEquals(setOf(AssetId("OWN_1_L0_001")), r.manager.added, "own and foreign photos reach the album in one id form: the gallery's")
    }

    @Test
    fun `an own photo the current policy excludes is not gathered`() = runTest {
        val r = rig(cfg = config(from = "2026-09-15T00:00:00Z"))
        r.own("EARLY", "2026-09-10T00:00:00Z")
        r.own("LATE", "2026-09-20T00:00:00Z")
        r.gather.gather("E2")
        assertEquals(setOf(AssetId("LATE")), r.manager.added)
    }

    @Test
    fun `a departed own photo whose row is gone is not gathered`() = runTest {
        val r = rig()
        r.own("GONE", "2026-09-10T00:00:00Z")
        // A photo that left the library loses its row to the walk's deletion (capability `photo-sharing`).
        r.ledger.deleteKeys(listOf("GONE.HEIC"))
        r.gather.gather("E2")
        assertTrue(r.manager.added.isEmpty())
    }

    @Test
    fun `foreign photos in this union are gathered but an import made for another event is not`() = runTest {
        val r = rig()
        r.imported("PEER", "IN_UNION", "LOCAL-IN")
        r.imported("PEER", "OTHER_EVENT", "LOCAL-OTHER", eventId = "E1")
        r.gather.gather("E2")
        assertEquals(setOf(AssetId("LOCAL-IN")), r.manager.added)
    }

    @Test
    fun `a folder album gathers only this event’s received photos — never the member’s own`() = runTest {
        // `changes/archive/2026-09-30-android-event-album` D5: on Android the album is the folder received photos
        // live in, and gathering
        // moves them there; an own photo is the camera's file, never the app's to move.
        val r = rig(kind = AlbumKind.FOLDER)
        r.own("OWN", "2026-09-10T00:00:00Z")
        r.imported("PEER", "IN_UNION", "LOCAL-IN")
        r.imported("PEER", "OTHER_EVENT", "LOCAL-OTHER", eventId = "E1")

        r.gather.gather("E2")
        r.gather.gather("E2")

        assertEquals(setOf(AssetId("LOCAL-IN")), r.manager.added, "a second gather files nothing new")
    }

    @Test
    fun `an import no reconcile of this event tagged is not gathered`() = runTest {
        val r = rig()
        r.own("OWN", "2026-09-10T00:00:00Z")
        r.imported("PEER", "UNTAGGED", "LOCAL-UNTAGGED", eventId = null)
        r.gather.gather("E2")
        assertEquals(setOf(AssetId("OWN")), r.manager.added)
    }

    @Test
    fun `a gather adds in batches no larger than the size and a failing batch does not stop the rest`() = runTest {
        val r = rig(failingCall = 1)
        listOf("A", "B", "C", "D", "E").forEach { r.own(it, "2026-09-10T00:00:00Z") }
        r.gather.gather("E2")
        assertEquals(3, r.manager.calls.size)
        assertTrue(r.manager.calls.all { it.size <= 2 })
        assertEquals(setOf(AssetId("A"), AssetId("B"), AssetId("C"), AssetId("D"), AssetId("E")), r.manager.added)
    }

    @Test
    fun `an opted-out membership gathers nothing`() = runTest {
        val r = rig(cfg = config(saveToAlbum = false))
        r.own("OWN", "2026-09-10T00:00:00Z")
        r.gather.gather("E2")
        assertTrue(r.manager.calls.isEmpty())
    }

    @Test
    fun `a gather for an event no longer joined gathers nothing`() = runTest {
        val r = rig(cfg = config(eventId = "E3"))
        r.own("OWN", "2026-09-10T00:00:00Z")
        r.gather.gather("E2")
        assertTrue(r.manager.calls.isEmpty())
    }

    @Test
    fun `a gather without usable photo access gathers nothing`() = runTest {
        val r = rig(granted = false)
        r.own("OWN", "2026-09-10T00:00:00Z")
        r.gather.gather("E2")
        assertTrue(r.manager.calls.isEmpty())
    }

    @Test
    fun `a queued gather runs after the running one and reads the config current when it runs`() = runTest {
        val r = rig()
        r.own("OWN", "2026-09-10T00:00:00Z")
        val gate = CompletableDeferred<Unit>().also { r.manager.gate = it }
        val first = async { r.gather.gather("E2") }
        yield()
        val second = async { r.gather.gather("E2") }
        yield()
        // Opt out while the first gather holds the lock: the queued one must see it.
        r.config.save(config(saveToAlbum = false))
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, r.manager.calls.size, "only the gather that started before the opt-out placed anything")
    }

    // ---- the grant trigger: which permission emissions start a gather ----

    @Test
    fun `the first permission emission never starts a gather even when usable`() = runTest {
        val r = rig()
        r.own("OWN", "2026-09-10T00:00:00Z")
        r.gather.onAccessObserved(usable = true)
        advanceUntilIdle()
        assertTrue(r.manager.calls.isEmpty(), "an already-granted cold launch gathers nothing")
    }

    @Test
    fun `access becoming usable while running starts a gather`() = runTest {
        val r = rig()
        r.own("OWN", "2026-09-10T00:00:00Z")
        r.gather.onAccessObserved(usable = false)
        r.gather.onAccessObserved(usable = true)
        advanceUntilIdle()
        assertEquals(setOf(AssetId("OWN")), r.manager.added)
    }

    @Test
    fun `a usable to usable change does not start another gather`() = runTest {
        val r = rig()
        r.own("OWN", "2026-09-10T00:00:00Z")
        r.gather.onAccessObserved(usable = false)
        r.gather.onAccessObserved(usable = true)
        r.gather.onAccessObserved(usable = true) // LIMITED -> GRANTED
        advanceUntilIdle()
        assertEquals(1, r.manager.calls.size)
    }

    @Test
    fun `a grant with no membership starts nothing`() = runTest {
        val r = rig(cfg = null)
        r.gather.onAccessObserved(usable = false)
        r.gather.onAccessObserved(usable = true)
        advanceUntilIdle()
        assertTrue(r.manager.calls.isEmpty())
    }
}
