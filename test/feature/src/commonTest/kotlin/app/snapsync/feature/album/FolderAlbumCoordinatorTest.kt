@file:OptIn(CompositionOnly::class)

package app.snapsync.feature.album

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.feature.support.inboundOf
import app.snapsync.feature.support.testShared
import app.snapsync.format.TimestampText
import app.snapsync.inbound.CompositionOnly
import app.snapsync.mock.LibraryAssets
import app.snapsync.mock.PhotoLibraryMock
import app.snapsync.model.AlbumId
import app.snapsync.model.AssetRef
import app.snapsync.model.EventConfig
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.GalleryImportRequest
import app.snapsync.ports.ImportFile
import app.snapsync.ports.ImportOutcome
import app.snapsync.ports.LibraryAlbumKind
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ResourceKind
import app.snapsync.services.album.AlbumMapService
import app.snapsync.services.gallery.GalleryAlbums
import app.snapsync.services.gallery.assetId
import app.snapsync.services.gallery.correlation
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The event album where the library's album is the FOLDER a photo lives in (Android; capability `event-album`,
 * `changes/archive/2026-09-30-android-event-album` D4, D5): over the photo-library mock playing an Android library,
 * with the REAL album
 * map. The collection library's rules are [AlbumCoordinatorTest]'s and are unchanged.
 */
class FolderAlbumCoordinatorTest {

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
    private val library = PhotoLibraryMock(albumKind = LibraryAlbumKind.FOLDER)
    private val gallery: Gallery = library.port().apply {
        inbound(GalleryHandlers(onImportPlaceholder = inboundOf {}, onImportSettled = inboundOf {}))
    }
    private val store = AlbumMapService(testShared())
    private val coordinator = AlbumCoordinator(GalleryAlbums(gallery), store)

    /** A received photo, imported the way the download controller imports it: into [album], then settled. */
    private suspend fun receive(name: String, album: AlbumId?): PlatformAssetId {
        val file = ImportFile("staged:/$name", "image/jpeg", ResourceKind.PHOTO, "$name.JPG")
        val result = gallery.import(
            GalleryImportRequest(
                AssetRef(testDeviceId("other"), AssetId(name)).correlation(),
                listOf(file),
                Instant.parse("2026-09-30T10:00:00Z"),
                album?.let(::PlatformAlbumId),
            ),
        )
        val id = assertIs<ImportOutcome.Imported>(result).created
        album?.let { coordinator.onImportedInto(event, it) }
        return id
    }

    /** The app's album folder [asset] lives in, as the library reads it — `null`: the camera folder. */
    private suspend fun folderOf(asset: PlatformAssetId): String? =
        library.operator.albums().firstOrNull { asset.value in it.photos }?.id

    private suspend fun ensure(optIn: Boolean = true) = coordinator.ensureAlbum(
        testEventId(event.value),
        "Party",
        saveToAlbum = true,
        optIn = optIn,
    )

    @Test
    fun `a fresh album is used although an empty folder does not resolve`() = runTest {
        val album = assertNotNull(ensure())
        assertEquals(
            album,
            coordinator.albumIdFor(testEventId(event.value), saveToAlbum = true),
            "the first received photo goes into it",
        )
        assertEquals(album, ensure(optIn = false), "a launch's replay reuses it rather than making a second")
        assertEquals(1, library.operator.albums().size)
    }

    @Test
    fun `a received photo lands in the album and the album is then filled`() = runTest {
        val album = assertNotNull(ensure())
        val id = receive("R1", coordinator.albumIdFor(testEventId(event.value), saveToAlbum = true))
        assertEquals(album, folderOf(id))
        assertTrue(store.filled(testEventId(event.value)))
    }

    @Test
    fun `an album the member deleted sends later photos to the camera folder until they opt in again`() = runTest {
        val album = assertNotNull(ensure())
        receive("R1", coordinator.albumIdFor(testEventId(event.value), saveToAlbum = true))
        library.operator.deleteAlbum(album)

        assertNull(
            coordinator.albumIdFor(testEventId(event.value), saveToAlbum = true),
            "no folder is brought back by an import",
        )
        val later = receive("R2", coordinator.albumIdFor(testEventId(event.value), saveToAlbum = true))
        assertNull(folderOf(later), "the photo arrives in the camera folder")
        assertNull(ensure(optIn = false), "a launch's replay recreates nothing")
        assertEquals(1, library.operator.albums().size)

        val recreated = assertNotNull(ensure(optIn = true), "an opt-in recreates it")
        assertNotEquals(album, recreated)
        assertEquals(recreated, coordinator.albumIdFor(testEventId(event.value), saveToAlbum = true))
    }

    @Test
    fun `an album the member renamed counts as deleted`() = runTest {
        val album = assertNotNull(ensure())
        receive("R1", coordinator.albumIdFor(testEventId(event.value), saveToAlbum = true))
        library.operator.renameAlbum(album, "Party 2026")

        assertNull(
            coordinator.albumIdFor(testEventId(event.value), saveToAlbum = true),
            "the renamed folder is not followed, nor recreated",
        )
    }

    @Test
    fun `own photos are never placed — and received ones are moved in`() = runTest {
        val album = assertNotNull(ensure())
        val own = PlatformAssetId("camera-1")
        library.add(
            LibraryAssets.photo(
                own.value,
                "2026-09-30T10:00:00Z",
                resources = listOf(LibraryAssets.primaryResource("IMG_1.JPG")),
            ),
        )
        val received = receive("R1", album = null)

        coordinator.place(testEventId(event.value), listOf(own.assetId()))
        coordinator.placeReceived(testEventId(event.value), listOf(received.assetId()))

        assertNull(folderOf(own), "the member's own photo stays in the camera folder")
        assertEquals(album, folderOf(received), "the received photo moved into the album")
        assertTrue(store.filled(testEventId(event.value)), "a move that landed fills the album")
    }

    @Test
    fun `a filled album that still holds its photos keeps receiving them`() = runTest {
        val album = assertNotNull(ensure())
        receive("R1", coordinator.albumIdFor(testEventId(event.value), saveToAlbum = true))
        assertTrue(store.filled(testEventId(event.value)))

        assertEquals(album, coordinator.albumIdFor(testEventId(event.value), saveToAlbum = true))
    }

    @Test
    fun `an import into an album that is no longer the event's fills nothing`() = runTest {
        // A recreation raced the import: the photo landed in the old album, which is not the one to watch.
        assertNotNull(ensure())
        coordinator.onImportedInto(event, "an-older-album")
        assertFalse(store.filled(testEventId(event.value)))
    }

    @Test
    fun `filing nothing or filing with no album yet fills nothing`() = runTest {
        coordinator.placeReceived(testEventId(event.value), listOf(receive("R1", album = null).assetId()))
        assertFalse(store.filled(testEventId(event.value)), "no album was ever created")

        assertNotNull(ensure())
        coordinator.placeReceived(testEventId(event.value), emptyList())
        assertFalse(store.filled(testEventId(event.value)), "an empty batch moves nothing")
    }

    @Test
    fun `an import settled for the joined membership fills its album and none fills while unjoined`() = runTest {
        val album = assertNotNull(ensure())
        coordinator.onImportedInto(null, album)
        assertFalse(store.filled(testEventId(event.value)), "no membership, nothing filled")
        coordinator.onImportedInto(joined, album)
        assertTrue(store.filled(testEventId(event.value)))
    }
}
