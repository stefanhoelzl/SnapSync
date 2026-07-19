package app.snapsync.feature.album

import app.snapsync.ports.AlbumManager
import app.snapsync.ports.AlbumMapStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeAlbumManager(
    var createResult: String? = "album-1",
    var existingIds: MutableSet<String> = mutableSetOf(),
) : AlbumManager {
    var createCount = 0
    val added = mutableListOf<Pair<String, List<String>>>()

    override suspend fun ensureCreated(name: String): String? {
        createCount++
        return createResult?.also { existingIds.add(it) }
    }

    override suspend fun exists(albumLocalId: String): Boolean = albumLocalId in existingIds

    override suspend fun assetIdsInAlbums(titles: Set<String>, since: String): Set<String> = emptySet()

    override suspend fun add(albumLocalId: String, rawLocalIds: List<String>) {
        added.add(albumLocalId to rawLocalIds)
    }
}

private class InMemoryAlbumMapStore : AlbumMapStore {
    val map = mutableMapOf<String, String>()
    override fun get(eventId: String): String? = map[eventId]
    override fun put(eventId: String, albumLocalId: String) { map[eventId] = albumLocalId }
}

class AlbumCoordinatorTest {

    private val event = "e1"

    @Test
    fun `ensureAlbum creates and stores when absent`() = runTest {
        val manager = FakeAlbumManager(createResult = "album-X")
        val store = InMemoryAlbumMapStore()
        val id = AlbumCoordinator(manager, store).ensureAlbum(event, "Birthday", saveToAlbum = true)
        assertEquals("album-X", id)
        assertEquals("album-X", store.get(event))
        assertEquals(1, manager.createCount)
    }

    @Test
    fun `ensureAlbum reuses an existing album without recreating`() = runTest {
        val manager = FakeAlbumManager(existingIds = mutableSetOf("album-X"))
        val store = InMemoryAlbumMapStore().apply { put(event, "album-X") }
        val id = AlbumCoordinator(manager, store).ensureAlbum(event, "Birthday", saveToAlbum = true)
        assertEquals("album-X", id)
        assertEquals(0, manager.createCount) // reused, not recreated
    }

    @Test
    fun `ensureAlbum recreates and overwrites when the stored album is dangling`() = runTest {
        // Stored id no longer resolves (user deleted the album).
        val manager = FakeAlbumManager(createResult = "album-NEW", existingIds = mutableSetOf())
        val store = InMemoryAlbumMapStore().apply { put(event, "album-OLD") }
        val id = AlbumCoordinator(manager, store).ensureAlbum(event, "Birthday", saveToAlbum = true)
        assertEquals("album-NEW", id)
        assertEquals("album-NEW", store.get(event)) // overwritten
        assertEquals(1, manager.createCount)
    }

    @Test
    fun `ensureAlbum returns null on a creation failure and stores nothing`() = runTest {
        val manager = FakeAlbumManager(createResult = null)
        val store = InMemoryAlbumMapStore()
        assertNull(AlbumCoordinator(manager, store).ensureAlbum(event, "Birthday", saveToAlbum = true))
        assertNull(store.get(event))
    }

    @Test
    fun `ensureAlbum is a no-op for an opted-out membership`() = runTest {
        // The opt-in gate lives HERE (migration step 8 C3, formerly the shell's `ensureAlbumIfOptedIn`):
        // callers call unconditionally, and an opted-out membership creates and stores nothing.
        val manager = FakeAlbumManager(createResult = "album-X")
        val store = InMemoryAlbumMapStore()
        assertNull(AlbumCoordinator(manager, store).ensureAlbum(event, "Birthday", saveToAlbum = false))
        assertEquals(0, manager.createCount)
        assertNull(store.get(event))
    }

    @Test
    fun `ensureAlbum is a no-op for an empty name`() = runTest {
        // A nameless membership cannot title an album (the name arrives via the later fetch); same
        // guard the shell helper held (`cfg.name.isNotEmpty()`).
        val manager = FakeAlbumManager(createResult = "album-X")
        val store = InMemoryAlbumMapStore()
        assertNull(AlbumCoordinator(manager, store).ensureAlbum(event, "", saveToAlbum = true))
        assertEquals(0, manager.createCount)
    }

    @Test
    fun `ensureAlbum without granted access is a no-op — the access fact is the coordinator's guard`() = runTest {
        // The Provision flow passes the fact; the rule (no album without full photo access) is
        // this feature's leading guard since the migration finale, so no caller can forget it.
        val manager = FakeAlbumManager(createResult = "album-X")
        val store = InMemoryAlbumMapStore()
        assertNull(
            AlbumCoordinator(manager, store)
                .ensureAlbum("E", "Birthday", saveToAlbum = true, granted = false),
        )
        assertEquals(0, manager.createCount)
        assertNull(store.get("E"))
    }

    @Test
    fun `albumIdFor returns the stored album only for an opted-in membership`() = runTest {
        val manager = FakeAlbumManager()
        val store = InMemoryAlbumMapStore().apply { put(event, "album-X") }
        val coordinator = AlbumCoordinator(manager, store)
        assertEquals("album-X", coordinator.albumIdFor(event, saveToAlbum = true))
        assertNull(coordinator.albumIdFor(event, saveToAlbum = false)) // opt-out: no import-time add
        assertNull(coordinator.albumIdFor("other", saveToAlbum = true)) // no album ever created
    }

    @Test
    fun `place adds to the stored album`() = runTest {
        val manager = FakeAlbumManager(existingIds = mutableSetOf("album-X"))
        val store = InMemoryAlbumMapStore().apply { put(event, "album-X") }
        AlbumCoordinator(manager, store).place(event, listOf("A/L0/1", "B/L0/1"))
        assertEquals(listOf("album-X" to listOf("A/L0/1", "B/L0/1")), manager.added)
    }

    @Test
    fun `place skips when no album exists yet`() = runTest {
        val manager = FakeAlbumManager()
        val store = InMemoryAlbumMapStore() // empty
        AlbumCoordinator(manager, store).place(event, listOf("A/L0/1"))
        assertTrue(manager.added.isEmpty())
    }

    @Test
    fun `place is a no-op for an empty id list`() = runTest {
        val manager = FakeAlbumManager(existingIds = mutableSetOf("album-X"))
        val store = InMemoryAlbumMapStore().apply { put(event, "album-X") }
        AlbumCoordinator(manager, store).place(event, emptyList())
        assertTrue(manager.added.isEmpty())
    }

    @Test
    fun `place never throws when the manager add fails`() = runTest {
        val manager = object : AlbumManager {
            override suspend fun ensureCreated(name: String): String? = "x"
            override suspend fun exists(albumLocalId: String): Boolean = true
            override suspend fun assetIdsInAlbums(titles: Set<String>, since: String): Set<String> = emptySet()
            override suspend fun add(albumLocalId: String, rawLocalIds: List<String>) = error("boom")
        }
        val store = InMemoryAlbumMapStore().apply { put(event, "album-X") }
        AlbumCoordinator(manager, store).place(event, listOf("A/L0/1")) // must not throw
    }
}
