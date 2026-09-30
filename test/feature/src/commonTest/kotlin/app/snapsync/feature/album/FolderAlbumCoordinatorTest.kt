package app.snapsync.feature.album

import app.snapsync.mock.PhotoLibraryMock
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.AlbumId
import app.snapsync.model.AlbumKind
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.ImportRequest
import app.snapsync.model.ImportResult
import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.StagedResource
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryHandlers
import app.snapsync.services.album.AlbumMapService
import app.snapsync.services.gallery.GalleryAlbums
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The event album where the library's album is the FOLDER a photo lives in (Android; capability `event-album`,
 * `changes/android-event-album` D4, D5): over the photo-library mock playing an Android library, with the REAL album
 * map. The collection library's rules are [AlbumCoordinatorTest]'s and are unchanged.
 */
class FolderAlbumCoordinatorTest {

    private val event = "e1"
    private val library = PhotoLibraryMock().apply { operator.albumKind = AlbumKind.FOLDER }
    private val gallery: Gallery = library.port().apply { listen(GalleryHandlers(onChanged = {}, onImportPlaceholder = { _, _ -> }, onImportSettled = { _, _ -> })) }
    private val store = AlbumMapService(inMemoryPreferences(), inMemorySecureStore())
    private val coordinator = AlbumCoordinator(GalleryAlbums(gallery), store, kind = AlbumKind.FOLDER)

    /** A received photo, imported the way the download controller imports it: into [album], then settled. */
    private suspend fun receive(name: String, album: AlbumId?): AssetId {
        val staged = StagedResource("k-$name", ResourceRole.PRIMARY.wire, "image/jpeg", "$name.JPG", "staged:/$name")
        val result = gallery.import(ImportRequest(AssetRef("other", AssetId(name)), listOf(staged), "2026-09-30T10:00:00Z", album))
        val id = assertIs<ImportResult.Imported>(result).createdLocalId
        album?.let { coordinator.onImportedInto(event, it) }
        return id
    }

    private suspend fun ensure(optIn: Boolean = true) = coordinator.ensureAlbum(event, "Party", saveToAlbum = true, optIn = optIn)

    @Test
    fun `a fresh album is used although an empty folder does not resolve`() = runTest {
        val album = assertNotNull(ensure())
        assertEquals(album, coordinator.albumIdFor(event, saveToAlbum = true), "the first received photo goes into it")
        assertEquals(album, ensure(optIn = false), "a launch's replay reuses it rather than making a second")
        assertEquals(1, library.operator.created.size)
    }

    @Test
    fun `a received photo lands in the album and the album is then filled`() = runTest {
        val album = assertNotNull(ensure())
        val id = receive("R1", coordinator.albumIdFor(event, saveToAlbum = true))
        assertEquals(album, library.operator.folderOf(id))
        assertTrue(store.filled(event))
    }

    @Test
    fun `an album the member deleted sends later photos to the camera folder until they opt in again`() = runTest {
        val album = assertNotNull(ensure())
        receive("R1", coordinator.albumIdFor(event, saveToAlbum = true))
        library.operator.delete(album)

        assertNull(coordinator.albumIdFor(event, saveToAlbum = true), "no folder is brought back by an import")
        val later = receive("R2", coordinator.albumIdFor(event, saveToAlbum = true))
        assertNull(library.operator.folderOf(later), "the photo arrives in the camera folder")
        assertNull(ensure(optIn = false), "a launch's replay recreates nothing")
        assertEquals(1, library.operator.created.size)

        val recreated = assertNotNull(ensure(optIn = true), "an opt-in recreates it")
        assertNotEquals(album, recreated)
        assertEquals(recreated, coordinator.albumIdFor(event, saveToAlbum = true))
    }

    @Test
    fun `an album the member renamed counts as deleted`() = runTest {
        val album = assertNotNull(ensure())
        receive("R1", coordinator.albumIdFor(event, saveToAlbum = true))
        library.operator.rename(album, "Party 2026")

        assertNull(coordinator.albumIdFor(event, saveToAlbum = true), "the renamed folder is not followed, nor recreated")
    }

    @Test
    fun `own photos are never placed — and received ones are moved in`() = runTest {
        val album = assertNotNull(ensure())
        val own = AssetId("camera-1")
        library.operator.add(RawAsset(own, "2026-09-30T10:00:00Z", listOf(RawResource(ResourceRole.PRIMARY, "image/jpeg", "IMG_1.JPG", Unit))))
        val received = receive("R1", album = null)

        coordinator.place(event, listOf(own))
        coordinator.placeReceived(event, listOf(received))

        assertNull(library.operator.folderOf(own), "the member's own photo stays in the camera folder")
        assertEquals(album, library.operator.folderOf(received), "the received photo moved into the album")
        assertTrue(store.filled(event), "a move that landed fills the album")
    }
}
