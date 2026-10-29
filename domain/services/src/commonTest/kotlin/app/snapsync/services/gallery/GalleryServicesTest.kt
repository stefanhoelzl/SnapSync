package app.snapsync.services.gallery

import app.snapsync.model.AssetPresence
import app.snapsync.ports.GalleryAlbum
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.WriteOutcome
import app.snapsync.testids.testAssetId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gallery services — the decisions made over the thin [app.snapsync.ports.GalleryReader]: which answer is
 * authoritative, which grant may say a photo is gone, how the event album is filled. The gallery below is a
 * [ScriptedGallery], because what these services must NOT ask the gallery is as much the point as what they answer.
 */
class GalleryServicesTest {

    private fun photo(id: String) = platformPhoto(id)

    // ---- GalleryAssetPresence -----------------------------------------------------------------------

    @Test
    fun `a full grant answers present and absent for every id asked`() = runTest {
        val presence = GalleryAssetPresence(ScriptedGallery(assets = listOf(photo("A"))))
        assertEquals(
            mapOf(testAssetId("A") to AssetPresence.PRESENT, testAssetId("GONE") to AssetPresence.ABSENT),
            presence.presence(setOf(testAssetId("A"), testAssetId("GONE"))),
        )
    }

    @Test
    fun `no other grant may say absent and none asks the gallery`() = runTest {
        for (access in LibraryGrant.entries - LibraryGrant.GRANTED) {
            val gallery = ScriptedGallery(access = access, assets = listOf(photo("A")))
            assertEquals(
                mapOf(testAssetId("A") to AssetPresence.UNKNOWN, testAssetId("GONE") to AssetPresence.UNKNOWN),
                GalleryAssetPresence(gallery).presence(setOf(testAssetId("A"), testAssetId("GONE"))),
                "$access: a partial view's miss is not evidence of absence",
            )
            assertTrue(gallery.calls.isEmpty(), "$access")
        }
    }

    @Test
    fun `an unreadable gallery answers unknown and asking about nothing answers nothing`() = runTest {
        assertEquals(
            mapOf(testAssetId("A") to AssetPresence.UNKNOWN),
            GalleryAssetPresence(ScriptedGallery(readable = false)).presence(setOf(testAssetId("A"))),
        )
        val gallery = ScriptedGallery()
        assertEquals(emptyMap(), GalleryAssetPresence(gallery).presence(emptySet()))
        assertTrue(gallery.calls.isEmpty())
    }

    // ---- GalleryAlbums ------------------------------------------------------------------------------

    @Test
    fun `an album is created resolves while it exists and not when unreadable`() = runTest {
        val gallery = ScriptedGallery(albums = listOf(GalleryAlbum(PlatformAlbumId("album-1"), "Trip")))
        val albums = GalleryAlbums(gallery)
        assertEquals("album-new", albums.ensureCreated("Trip"))
        assertEquals(null, GalleryAlbums(ScriptedGallery(created = null)).ensureCreated("Trip"), "a refused creation")
        assertTrue(albums.exists("album-1"))
        assertFalse(albums.exists("album-2"))
        gallery.readable = false
        assertFalse(albums.exists("album-1"))
    }

    @Test
    fun `adds reach the gallery as one set and nothing is added for nothing`() = runTest {
        val gallery = ScriptedGallery()
        val albums = GalleryAlbums(gallery)
        albums.add("album-1", emptyList())
        assertTrue(gallery.adds.isEmpty())
        albums.add("album-1", listOf(testAssetId("A"), testAssetId("B"), testAssetId("A")))
        gallery.addOutcome = WriteOutcome.Failed("gone")
        albums.add("album-1", listOf(testAssetId("C"))) // a failed add is logged, never thrown
        assertEquals(
            listOf(
                "album-1" to setOf(PlatformAssetId("A"), PlatformAssetId("B")),
                "album-1" to setOf(PlatformAssetId("C")),
            ),
            gallery.adds,
        )
    }
}
