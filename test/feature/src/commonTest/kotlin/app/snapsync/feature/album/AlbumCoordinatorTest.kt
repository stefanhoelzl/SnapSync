package app.snapsync.feature.album

import app.snapsync.data.gallery.assetId
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.feature.support.testShared
import app.snapsync.format.TimestampText
import app.snapsync.mock.inMemoryGallery
import app.snapsync.model.EventConfig
import app.snapsync.ports.GalleryAlbum
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.WriteOutcome
import app.snapsync.services.album.AlbumMapService
import app.snapsync.services.gallery.GalleryAlbums
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testEventId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    /** What the library answers an add it does not throw on. */
    private val addOutcome: WriteOutcome = WriteOutcome.Ok,
) : GalleryReader by inMemoryGallery(MutableStateFlow(emptyList())) {
    var createCount = 0
    val added = mutableListOf<Pair<String, List<AssetId>>>()

    override suspend fun createAlbum(title: String): PlatformAlbumId? {
        createCount++
        return createResult?.also { existingIds.add(it) }?.let(::PlatformAlbumId)
    }

    override suspend fun albumsById(ids: Set<PlatformAlbumId>): GalleryRead<List<GalleryAlbum>> =
        GalleryRead.Read(ids.filter { it.value in existingIds }.map { GalleryAlbum(it, "Birthday") })

    override suspend fun addToAlbum(album: PlatformAlbumId, assets: Set<PlatformAssetId>): WriteOutcome {
        if (failAdd) error("boom")
        added.add(album.value to assets.map { it.assetId() })
        return addOutcome
    }
}

/** The REAL album map, over in-memory user defaults. */
private fun albumMap() = AlbumMapService(testShared())

class AlbumCoordinatorTest {

    private val event = testEventId("e1")

    /** The joined membership of [event], saving to its album. */
    private val joined = EventConfig(
        eventId = testEventId(event.value),
        name = "Party",
        minPhotoDate = CaptureCutoff(TimestampText.parse("2026-09-01T00:00:00Z")),
        endsAt = EventEnd(TimestampText.parse("2026-09-30T00:00:00Z")),
        maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-09-30T00:00:00Z")),
        deletesAt = DeletesAt(TimestampText.parse("2026-10-30T00:00:00Z")),
        saveToAlbum = true,
    )

    @Test
    fun `ensureAlbum creates and stores when absent`() = runTest {
        val manager = FakeAlbumManager(createResult = "album-X")
        val store = albumMap()
        val id = AlbumCoordinator(
            GalleryAlbums(manager),
            store,
        ).ensureAlbum(testEventId(event.value), "Birthday", saveToAlbum = true)
        assertEquals("album-X", id)
        assertEquals("album-X", store.get(testEventId(event.value)))
        assertEquals(1, manager.createCount)
    }

    @Test
    fun `ensureAlbum reuses an existing album without recreating`() = runTest {
        val manager = FakeAlbumManager(existingIds = mutableSetOf("album-X"))
        val store = albumMap().apply { put(testEventId(event.value), "album-X") }
        val id = AlbumCoordinator(
            GalleryAlbums(manager),
            store,
        ).ensureAlbum(testEventId(event.value), "Birthday", saveToAlbum = true)
        assertEquals("album-X", id)
        assertEquals(0, manager.createCount) // reused, not recreated
    }

    @Test
    fun `ensureAlbum recreates and overwrites when the stored album is dangling`() = runTest {
        // Stored id no longer resolves (user deleted the album).
        val manager = FakeAlbumManager(createResult = "album-NEW", existingIds = mutableSetOf())
        val store = albumMap().apply { put(testEventId(event.value), "album-OLD") }
        val id = AlbumCoordinator(
            GalleryAlbums(manager),
            store,
        ).ensureAlbum(testEventId(event.value), "Birthday", saveToAlbum = true)
        assertEquals("album-NEW", id)
        assertEquals("album-NEW", store.get(testEventId(event.value))) // overwritten
        assertEquals(1, manager.createCount)
    }

    @Test
    fun `a launch's grant replay does not recreate a deleted album`() = runTest {
        // The member deleted the album; the replay is no opt-in, so it stays deleted.
        val manager = FakeAlbumManager(createResult = "album-NEW", existingIds = mutableSetOf())
        val store = albumMap().apply { put(testEventId(event.value), "album-OLD") }
        val id = AlbumCoordinator(
            GalleryAlbums(manager),
            store,
        ).ensureAlbum(testEventId(event.value), "Birthday", saveToAlbum = true, optIn = false)
        assertNull(id)
        assertEquals("album-OLD", store.get(testEventId(event.value))) // the map is untouched
        assertEquals(0, manager.createCount)
    }

    @Test
    fun `ensureAlbum returns null on a creation failure and stores nothing`() = runTest {
        val manager = FakeAlbumManager(createResult = null)
        val store = albumMap()
        assertNull(
            AlbumCoordinator(
                GalleryAlbums(manager),
                store,
            ).ensureAlbum(testEventId(event.value), "Birthday", saveToAlbum = true),
        )
        assertNull(store.get(testEventId(event.value)))
    }

    @Test
    fun `ensureAlbum is a no-op for an opted-out membership`() = runTest {
        // The opt-in gate lives HERE (migration step 8 C3, formerly the shell's `ensureAlbumIfOptedIn`):
        // callers call unconditionally, and an opted-out membership creates and stores nothing.
        val manager = FakeAlbumManager(createResult = "album-X")
        val store = albumMap()
        assertNull(
            AlbumCoordinator(
                GalleryAlbums(manager),
                store,
            ).ensureAlbum(testEventId(event.value), "Birthday", saveToAlbum = false),
        )
        assertEquals(0, manager.createCount)
        assertNull(store.get(testEventId(event.value)))
    }

    @Test
    fun `ensureAlbum without granted access is a no-op — the access fact is the coordinator’s guard`() = runTest {
        // The join's steps pass the fact; the rule (no album without full photo access) is
        // this feature's leading guard since the migration finale, so no caller can forget it.
        val manager = FakeAlbumManager(createResult = "album-X")
        val store = albumMap()
        assertNull(
            AlbumCoordinator(GalleryAlbums(manager), store)
                .ensureAlbum(testEventId("E"), "Birthday", saveToAlbum = true, hasUsableAccess = false),
        )
        assertEquals(0, manager.createCount)
        assertNull(store.get(testEventId("E")))
    }

    @Test
    fun `albumIdFor returns the stored album only for an opted-in membership`() = runTest {
        val manager = FakeAlbumManager()
        val store = albumMap().apply { put(testEventId(event.value), "album-X") }
        val coordinator = AlbumCoordinator(GalleryAlbums(manager), store)
        assertEquals("album-X", coordinator.albumIdFor(testEventId(event.value), saveToAlbum = true))
        assertNull(coordinator.albumIdFor(testEventId(event.value), saveToAlbum = false)) // opt-out: no import-time add
        assertNull(coordinator.albumIdFor(testEventId("other"), saveToAlbum = true)) // no album ever created
    }

    @Test
    fun `place adds to the stored album`() = runTest {
        val manager = FakeAlbumManager(existingIds = mutableSetOf("album-X"))
        val store = albumMap().apply { put(testEventId(event.value), "album-X") }
        AlbumCoordinator(
            GalleryAlbums(manager),
            store,
        ).place(testEventId(event.value), listOf(testAssetId("A_L0_1"), testAssetId("B_L0_1")))
        assertEquals(listOf("album-X" to listOf(testAssetId("A_L0_1"), testAssetId("B_L0_1"))), manager.added)
    }

    @Test
    fun `place skips when no album exists yet`() = runTest {
        val manager = FakeAlbumManager()
        val store = albumMap() // empty
        AlbumCoordinator(GalleryAlbums(manager), store).place(testEventId(event.value), listOf(testAssetId("A_L0_1")))
        assertTrue(manager.added.isEmpty())
    }

    @Test
    fun `place is a no-op for an empty id list`() = runTest {
        val manager = FakeAlbumManager(existingIds = mutableSetOf("album-X"))
        val store = albumMap().apply { put(testEventId(event.value), "album-X") }
        AlbumCoordinator(GalleryAlbums(manager), store).place(testEventId(event.value), emptyList())
        assertTrue(manager.added.isEmpty())
    }

    @Test
    fun `place never throws when the manager add fails`() = runTest {
        val manager = FakeAlbumManager(existingIds = mutableSetOf("album-X"), failAdd = true)
        val store = albumMap().apply { put(testEventId(event.value), "album-X") }
        AlbumCoordinator(
            GalleryAlbums(manager),
            store,
        ).place(testEventId(event.value), listOf(testAssetId("A_L0_1"))) // must not throw
    }

    @Test
    fun `place never throws when the library refuses the add`() = runTest {
        val manager = FakeAlbumManager(existingIds = mutableSetOf("album-X"), addOutcome = WriteOutcome.Failed("no"))
        val store = albumMap().apply { put(testEventId(event.value), "album-X") }
        AlbumCoordinator(
            GalleryAlbums(manager),
            store,
        ).place(testEventId(event.value), listOf(testAssetId("A_L0_1"))) // must not throw
        assertEquals(1, manager.added.size, "the add was asked once, and its refusal ends it")
    }

    @Test
    fun `received photos are added to a collection which has no filled state`() = runTest {
        // Only a folder album turns "emptied" into "deleted"; a collection is never marked filled.
        val manager = FakeAlbumManager(existingIds = mutableSetOf("album-X"))
        val store = albumMap().apply { put(testEventId(event.value), "album-X") }
        val coordinator = AlbumCoordinator(GalleryAlbums(manager), store)

        coordinator.placeReceived(testEventId(event.value), listOf(testAssetId("R_L0_1")))
        coordinator.onImportedInto(event, "album-X")

        assertEquals(listOf("album-X" to listOf(testAssetId("R_L0_1"))), manager.added)
        assertFalse(store.filled(testEventId(event.value)))
    }

    @Test
    fun `the joined membership's imports file into its album and none file while unjoined`() = runTest {
        val store = albumMap().apply { put(testEventId(event.value), "album-X") }
        val coordinator = AlbumCoordinator(GalleryAlbums(FakeAlbumManager()), store)
        assertEquals("album-X", coordinator.albumIdFor(joined))
        assertNull(coordinator.albumIdFor(joined.copy(saveToAlbum = false)), "the opt-in rule still holds")
        assertNull(coordinator.albumIdFor(null))
    }
}
