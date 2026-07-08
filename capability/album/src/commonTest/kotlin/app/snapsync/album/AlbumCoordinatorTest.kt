package app.snapsync.album

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
        val id = AlbumCoordinator(manager, store).ensureAlbum(event, "Birthday")
        assertEquals("album-X", id)
        assertEquals("album-X", store.get(event))
        assertEquals(1, manager.createCount)
    }

    @Test
    fun `ensureAlbum reuses an existing album without recreating`() = runTest {
        val manager = FakeAlbumManager(existingIds = mutableSetOf("album-X"))
        val store = InMemoryAlbumMapStore().apply { put(event, "album-X") }
        val id = AlbumCoordinator(manager, store).ensureAlbum(event, "Birthday")
        assertEquals("album-X", id)
        assertEquals(0, manager.createCount) // reused, not recreated
    }

    @Test
    fun `ensureAlbum recreates and overwrites when the stored album is dangling`() = runTest {
        // Stored id no longer resolves (user deleted the album).
        val manager = FakeAlbumManager(createResult = "album-NEW", existingIds = mutableSetOf())
        val store = InMemoryAlbumMapStore().apply { put(event, "album-OLD") }
        val id = AlbumCoordinator(manager, store).ensureAlbum(event, "Birthday")
        assertEquals("album-NEW", id)
        assertEquals("album-NEW", store.get(event)) // overwritten
        assertEquals(1, manager.createCount)
    }

    @Test
    fun `ensureAlbum returns null on a creation failure and stores nothing`() = runTest {
        val manager = FakeAlbumManager(createResult = null)
        val store = InMemoryAlbumMapStore()
        assertNull(AlbumCoordinator(manager, store).ensureAlbum(event, "Birthday"))
        assertNull(store.get(event))
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
            override suspend fun add(albumLocalId: String, rawLocalIds: List<String>) = error("boom")
        }
        val store = InMemoryAlbumMapStore().apply { put(event, "album-X") }
        AlbumCoordinator(manager, store).place(event, listOf("A/L0/1")) // must not throw
    }
}
