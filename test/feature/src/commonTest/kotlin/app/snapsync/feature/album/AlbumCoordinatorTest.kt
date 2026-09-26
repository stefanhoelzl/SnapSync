package app.snapsync.feature.album

import app.snapsync.model.AssetId
import app.snapsync.fake.inMemoryGallery
import app.snapsync.fake.inMemoryPreferences
import app.snapsync.fake.inMemorySecureStore
import app.snapsync.model.AlbumId
import app.snapsync.model.AlbumRecord
import app.snapsync.model.GalleryRead
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.GalleryReader
import kotlinx.coroutines.flow.MutableStateFlow
import app.snapsync.services.gallery.GalleryAlbums
import app.snapsync.services.album.AlbumMapService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The photo library's album surface, at the port: the in-memory gallery for every read the coordinator does not make,
 * with album creation, lookup and adds scripted and recorded — what the REAL [GalleryAlbums] is built over.
 */
private class FakeAlbumManager(
    var createResult: String? = "album-1",
    var existingIds: MutableSet<String> = mutableSetOf(),
    private val failAdd: Boolean = false,
) : GalleryReader by inMemoryGallery(MutableStateFlow(emptyList())) {
    var createCount = 0
    val added = mutableListOf<Pair<String, List<AssetId>>>()

    override suspend fun createAlbum(title: String): AlbumId? {
        createCount++
        return createResult?.also { existingIds.add(it) }
    }

    override suspend fun albumsById(ids: Set<AlbumId>): GalleryRead<List<AlbumRecord>> =
        GalleryRead.Read(ids.filter { it in existingIds }.map { AlbumRecord(it, "Birthday") })

    override suspend fun addToAlbum(album: AlbumId, assets: Set<AssetId>): WriteOutcome {
        if (failAdd) error("boom")
        added.add(album to assets.toList())
        return WriteOutcome.Ok
    }
}

/** The REAL album map, over in-memory user defaults. */
private fun albumMap() = AlbumMapService(inMemoryPreferences(), inMemorySecureStore())

class AlbumCoordinatorTest {

    private val event = "e1"

    @Test
    fun `ensureAlbum creates and stores when absent`() = runTest {
        val manager = FakeAlbumManager(createResult = "album-X")
        val store = albumMap()
        val id = AlbumCoordinator(GalleryAlbums(manager), store).ensureAlbum(event, "Birthday", saveToAlbum = true)
        assertEquals("album-X", id)
        assertEquals("album-X", store.get(event))
        assertEquals(1, manager.createCount)
    }

    @Test
    fun `ensureAlbum reuses an existing album without recreating`() = runTest {
        val manager = FakeAlbumManager(existingIds = mutableSetOf("album-X"))
        val store = albumMap().apply { put(event, "album-X") }
        val id = AlbumCoordinator(GalleryAlbums(manager), store).ensureAlbum(event, "Birthday", saveToAlbum = true)
        assertEquals("album-X", id)
        assertEquals(0, manager.createCount) // reused, not recreated
    }

    @Test
    fun `ensureAlbum recreates and overwrites when the stored album is dangling`() = runTest {
        // Stored id no longer resolves (user deleted the album).
        val manager = FakeAlbumManager(createResult = "album-NEW", existingIds = mutableSetOf())
        val store = albumMap().apply { put(event, "album-OLD") }
        val id = AlbumCoordinator(GalleryAlbums(manager), store).ensureAlbum(event, "Birthday", saveToAlbum = true)
        assertEquals("album-NEW", id)
        assertEquals("album-NEW", store.get(event)) // overwritten
        assertEquals(1, manager.createCount)
    }

    @Test
    fun `ensureAlbum returns null on a creation failure and stores nothing`() = runTest {
        val manager = FakeAlbumManager(createResult = null)
        val store = albumMap()
        assertNull(AlbumCoordinator(GalleryAlbums(manager), store).ensureAlbum(event, "Birthday", saveToAlbum = true))
        assertNull(store.get(event))
    }

    @Test
    fun `ensureAlbum is a no-op for an opted-out membership`() = runTest {
        // The opt-in gate lives HERE (migration step 8 C3, formerly the shell's `ensureAlbumIfOptedIn`):
        // callers call unconditionally, and an opted-out membership creates and stores nothing.
        val manager = FakeAlbumManager(createResult = "album-X")
        val store = albumMap()
        assertNull(AlbumCoordinator(GalleryAlbums(manager), store).ensureAlbum(event, "Birthday", saveToAlbum = false))
        assertEquals(0, manager.createCount)
        assertNull(store.get(event))
    }

    @Test
    fun `ensureAlbum without granted access is a no-op — the access fact is the coordinator's guard`() = runTest {
        // The Provision flow passes the fact; the rule (no album without full photo access) is
        // this feature's leading guard since the migration finale, so no caller can forget it.
        val manager = FakeAlbumManager(createResult = "album-X")
        val store = albumMap()
        assertNull(
            AlbumCoordinator(GalleryAlbums(manager), store)
                .ensureAlbum("E", "Birthday", saveToAlbum = true, hasUsableAccess = false),
        )
        assertEquals(0, manager.createCount)
        assertNull(store.get("E"))
    }

    @Test
    fun `albumIdFor returns the stored album only for an opted-in membership`() = runTest {
        val manager = FakeAlbumManager()
        val store = albumMap().apply { put(event, "album-X") }
        val coordinator = AlbumCoordinator(GalleryAlbums(manager), store)
        assertEquals("album-X", coordinator.albumIdFor(event, saveToAlbum = true))
        assertNull(coordinator.albumIdFor(event, saveToAlbum = false)) // opt-out: no import-time add
        assertNull(coordinator.albumIdFor("other", saveToAlbum = true)) // no album ever created
    }

    @Test
    fun `place adds to the stored album`() = runTest {
        val manager = FakeAlbumManager(existingIds = mutableSetOf("album-X"))
        val store = albumMap().apply { put(event, "album-X") }
        AlbumCoordinator(GalleryAlbums(manager), store).place(event, listOf(AssetId("A_L0_1"), AssetId("B_L0_1")))
        assertEquals(listOf("album-X" to listOf(AssetId("A_L0_1"), AssetId("B_L0_1"))), manager.added)
    }

    @Test
    fun `place skips when no album exists yet`() = runTest {
        val manager = FakeAlbumManager()
        val store = albumMap() // empty
        AlbumCoordinator(GalleryAlbums(manager), store).place(event, listOf(AssetId("A_L0_1")))
        assertTrue(manager.added.isEmpty())
    }

    @Test
    fun `place is a no-op for an empty id list`() = runTest {
        val manager = FakeAlbumManager(existingIds = mutableSetOf("album-X"))
        val store = albumMap().apply { put(event, "album-X") }
        AlbumCoordinator(GalleryAlbums(manager), store).place(event, emptyList())
        assertTrue(manager.added.isEmpty())
    }

    @Test
    fun `place never throws when the manager add fails`() = runTest {
        val manager = FakeAlbumManager(existingIds = mutableSetOf("album-X"), failAdd = true)
        val store = albumMap().apply { put(event, "album-X") }
        AlbumCoordinator(GalleryAlbums(manager), store).place(event, listOf(AssetId("A_L0_1"))) // must not throw
    }
}
