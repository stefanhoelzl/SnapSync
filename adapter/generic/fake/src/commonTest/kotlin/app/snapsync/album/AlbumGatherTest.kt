package app.snapsync.album

import app.snapsync.model.PermissionStatus
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.fake.inMemoryAlbumMapStore
import app.snapsync.fake.inMemoryDownloadStore
import app.snapsync.fake.inMemoryLedgerStore
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.album.AlbumGather
import app.snapsync.model.EventConfig
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.selectionPolicyFor
import app.snapsync.ports.AlbumManager
import app.snapsync.ports.AssetRef
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.EventUnionSource
import app.snapsync.ports.LogScope
import app.snapsync.ports.PlannedResource
import app.snapsync.ports.UnionAsset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private class RecordingAlbumManager(private val failingCall: Int? = null) : AlbumManager {
        val calls = mutableListOf<List<String>>()
        val added: Set<String> get() = calls.flatten().toSet()
        override suspend fun ensureCreated(name: String): String? = error("the gather never creates an album")
        override suspend fun exists(albumLocalId: String): Boolean = true
        override suspend fun assetIdsInAlbums(titles: Set<String>, since: String): Set<String> = emptySet()
        override suspend fun add(albumLocalId: String, rawLocalIds: List<String>) {
            calls += rawLocalIds
            if (calls.size == failingCall) error("boom")
        }
    }

    private class GateableUnion(var result: Result<List<UnionAsset>>) : EventUnionSource {
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun union(eventId: String): Result<List<UnionAsset>> {
            gate?.await()
            return result
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
        val union: GateableUnion,
        granted: Boolean,
        scope: CoroutineScope,
    ) {
        val cell = MutableStateFlow(cfg)
        val ledger = inMemoryLedgerStore()
        val downloads: DownloadStore = inMemoryDownloadStore()
        var granted = granted
        val gather = AlbumGather(
            configSource = object : ConfigSource {
                override val config: StateFlow<EventConfig?> = cell
            },
            ledger = ledger,
            policyFor = { c -> selectionPolicyFor(c, suppressedAssetIds = { emptySet() }, albumExcludedAssetIds = { emptySet() }) },
            union = union,
            downloads = downloads,
            identity = { SELF },
            photoAccess = liveGrant { if (this.granted) PermissionStatus.GRANTED else PermissionStatus.DENIED },
            coordinator = AlbumCoordinator(manager, inMemoryAlbumMapStore(mapOf("E2" to "ALBUM-2"))),
            scope = scope,
            logScope = LogScope.NoOp,
            batchSize = 2,
        )

        suspend fun own(assetId: String, creationDate: String, eventId: String = "E1") {
            ledger.recordUnlessSettled(
                LedgerEntry("$assetId.HEIC", assetId, LedgerState.COMPLETED, creationDate = creationDate),
            )
        }

        suspend fun imported(device: String, assetId: String, localId: String) {
            val ref = AssetRef(device, assetId)
            downloads.plan(ref, "2026-09-10T00:00:00Z", listOf(PlannedResource("$assetId.heic", "u", "primary", "image/heic", "a.heic")))
            downloads.markImported(ref, localId)
        }
    }

    private fun CoroutineScope.rig(
        cfg: EventConfig? = config(),
        union: List<UnionAsset> = emptyList(),
        failingCall: Int? = null,
        granted: Boolean = true,
    ) = Rig(cfg, RecordingAlbumManager(failingCall), GateableUnion(Result.success(union)), granted, this)

    private fun inUnion(device: String, assetId: String) = UnionAsset(device, assetId, "2026-09-10T00:00:00Z", emptyList())

    @Test
    fun `a photo carried over from an earlier event is gathered when this window admits it`() = runTest {
        val r = rig()
        r.own("OWN_1_L0_001", "2026-09-10T00:00:00Z", eventId = "E1")
        r.gather.gather("E2")
        assertEquals(setOf("OWN/1/L0/001"), r.manager.added, "normalized ids are reversed to raw localIdentifiers")
    }

    @Test
    fun `an own photo the current policy excludes is not gathered`() = runTest {
        val r = rig(cfg = config(from = "2026-09-15T00:00:00Z"))
        r.own("EARLY", "2026-09-10T00:00:00Z")
        r.own("LATE", "2026-09-20T00:00:00Z")
        r.gather.gather("E2")
        assertEquals(setOf("LATE"), r.manager.added)
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
        val r = rig(union = listOf(inUnion("PEER", "IN_UNION"), inUnion(SELF, "MINE")))
        r.imported("PEER", "IN_UNION", "LOCAL-IN")
        r.imported("PEER", "OTHER_EVENT", "LOCAL-OTHER")
        r.gather.gather("E2")
        assertEquals(setOf("LOCAL-IN"), r.manager.added)
    }

    @Test
    fun `a failed union read still gathers the own photos`() = runTest {
        val r = rig()
        r.union.result = Result.failure(IllegalStateException("offline"))
        r.own("OWN", "2026-09-10T00:00:00Z")
        r.imported("PEER", "IN_UNION", "LOCAL-IN")
        r.gather.gather("E2")
        assertEquals(setOf("OWN"), r.manager.added)
    }

    @Test
    fun `a gather adds in batches no larger than the size and a failing batch does not stop the rest`() = runTest {
        val r = rig(failingCall = 1)
        listOf("A", "B", "C", "D", "E").forEach { r.own(it, "2026-09-10T00:00:00Z") }
        r.gather.gather("E2")
        assertEquals(3, r.manager.calls.size)
        assertTrue(r.manager.calls.all { it.size <= 2 })
        assertEquals(setOf("A", "B", "C", "D", "E"), r.manager.added)
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
        val gate = CompletableDeferred<Unit>().also { r.union.gate = it }
        val first = async { r.gather.gather("E2") }
        yield()
        val second = async { r.gather.gather("E2") }
        yield()
        // Opt out while the first gather holds the lock: the queued one must see it.
        r.cell.value = config(saveToAlbum = false)
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
        r.gather.awaitStarted()
        assertTrue(r.manager.calls.isEmpty(), "an already-granted cold launch gathers nothing")
    }

    @Test
    fun `access becoming usable while running starts a gather`() = runTest {
        val r = rig()
        r.own("OWN", "2026-09-10T00:00:00Z")
        r.gather.onAccessObserved(usable = false)
        r.gather.onAccessObserved(usable = true)
        r.gather.awaitStarted()
        assertEquals(setOf("OWN"), r.manager.added)
    }

    @Test
    fun `a usable to usable change does not start another gather`() = runTest {
        val r = rig()
        r.own("OWN", "2026-09-10T00:00:00Z")
        r.gather.onAccessObserved(usable = false)
        r.gather.onAccessObserved(usable = true)
        r.gather.onAccessObserved(usable = true) // LIMITED -> GRANTED
        r.gather.awaitStarted()
        assertEquals(1, r.manager.calls.size)
    }

    @Test
    fun `a grant with no membership starts nothing`() = runTest {
        val r = rig(cfg = null)
        r.gather.onAccessObserved(usable = false)
        r.gather.onAccessObserved(usable = true)
        r.gather.awaitStarted()
        assertTrue(r.manager.calls.isEmpty())
    }

    private companion object {
        const val SELF = "SELF-DEVICE"
    }
}


/** A grant fake read at every access, so a test's `var` drives it live. */
private fun liveGrant(grant: () -> PermissionStatus): PhotoAccessStatusSource = object : PhotoAccessStatusSource {
    override val permission: StateFlow<PermissionStatus> get() = MutableStateFlow(grant())
}
