package app.snapsync.services.gallery

import app.snapsync.model.AlbumId
import app.snapsync.model.AlbumRecord
import app.snapsync.model.AssetFacts
import app.snapsync.model.AssetId
import app.snapsync.model.AssetPresence
import app.snapsync.model.CandidateRead
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.GalleryAccess
import app.snapsync.model.GalleryRead
import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.SELECTION_CALIBRATION
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.SelectionRule
import app.snapsync.model.WriteOutcome
import app.snapsync.model.captureCutoff
import app.snapsync.model.resourcesOf
import app.snapsync.ports.GalleryReader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The gallery services — the decisions made over the thin [GalleryReader]: which answer is authoritative, which grant
 * may say a photo is gone, which albums are denied. The gallery below is a scripted stand-in that records every call,
 * because what these services must NOT ask the gallery is as much the point as what they answer.
 */
class GalleryServicesTest {

    private class ScriptedGallery(
        var access: GalleryAccess = GalleryAccess.GRANTED,
        var readable: Boolean = true,
        var assets: List<RawAsset> = emptyList(),
        var albums: List<AlbumRecord> = emptyList(),
        var members: Map<AlbumId, Set<AssetId>> = emptyMap(),
        var created: AlbumId? = "album-new",
        var addOutcome: WriteOutcome = WriteOutcome.Ok,
    ) : GalleryReader {
        val calls = mutableListOf<String>()
        val adds = mutableListOf<Pair<AlbumId, Set<AssetId>>>()

        private fun <T> read(name: String, value: () -> T): GalleryRead<T> {
            calls += name
            return if (readable) GalleryRead.Read(value()) else GalleryRead.NotReadable
        }

        override fun access(): GalleryAccess = access
        override suspend fun assets(policy: SelectionPolicy) = read("assets") { assets.map { it.facts } }
        override suspend fun assetsById(ids: Set<AssetId>) =
            read("assetsById") { assets.map { it.facts }.filter { it.assetId in ids } }
        override suspend fun resources(ids: Set<AssetId>) =
            read("resources($ids)") { assets.filter { it.facts.assetId in ids } }
        override suspend fun albums() = read("albums") { albums }
        override suspend fun albumsById(ids: Set<AlbumId>) = read("albumsById") { albums.filter { it.id in ids } }
        override suspend fun albumMembers(album: AlbumId, since: CaptureCutoff?) =
            read("albumMembers($album)") { members[album].orEmpty() }
        override suspend fun createAlbum(title: String): AlbumId? = created.also { calls += "createAlbum($title)" }
        override suspend fun addToAlbum(album: AlbumId, assets: Set<AssetId>): WriteOutcome {
            adds += album to assets
            return addOutcome
        }
    }

    private fun photo(id: String) = RawAsset(
        assetId = id,
        creationDate = "2026-06-01T10:00:00Z",
        rawResources = listOf(RawResource(ResourceRole.PRIMARY, "image/jpeg", "IMG.JPG", Unit)),
        facts = AssetFacts(id, CaptureDate("2026-06-01T10:00:00Z")),
    )

    private val policy = SelectionPolicy(listOf(SelectionRule.CaptureAfter(captureCutoff("2026-01-01T00:00:00Z"))))

    // ---- GalleryCandidateSource ---------------------------------------------------------------------

    @Test
    fun `candidates are the gallery's facts and the admitted ones' resources come in ONE request`() = runTest {
        val gallery = ScriptedGallery(assets = listOf(photo("A"), photo("B"), photo("C")))
        val read = assertIs<CandidateRead.Readable>(GalleryCandidateSource(gallery).candidates(policy))
        assertEquals(listOf("A", "B", "C"), read.candidates.map { it.facts.assetId })
        assertEquals(listOf("assets"), gallery.calls, "facts cost no resource read")

        val resources = resourcesOf(read.candidates.filter { it.facts.assetId != "B" })

        assertEquals(listOf("A-primary.jpg", "C-primary.jpg"), resources.map { it.filename })
        assertEquals(listOf("assets", "resources([A, C])"), gallery.calls, "one batch for every admitted asset")
    }

    @Test
    fun `one candidate asked alone reads only its own resources`() = runTest {
        val gallery = ScriptedGallery(assets = listOf(photo("A"), photo("B")))
        val read = assertIs<CandidateRead.Readable>(GalleryCandidateSource(gallery).candidates(policy))

        assertEquals(listOf("B-primary.jpg"), read.candidates[1].resources().map { it.filename })
        assertEquals("resources([B])", gallery.calls.last())
    }

    @Test
    fun `an unreadable gallery is not readable never a counted zero`() = runTest {
        assertEquals(CandidateRead.NotReadable, GalleryCandidateSource(ScriptedGallery(readable = false)).candidates(policy))
    }

    @Test
    fun `a gallery that becomes unreadable before the resource read yields no resources rather than wrong ones`() =
        runTest {
            val gallery = ScriptedGallery(assets = listOf(photo("A")))
            val read = assertIs<CandidateRead.Readable>(GalleryCandidateSource(gallery).candidates(policy))
            gallery.readable = false
            assertTrue(resourcesOf(read.candidates).isEmpty())
        }

    // ---- GalleryDiscovery ---------------------------------------------------------------------------

    @Test
    fun `only a full grant's walk is authoritative for deletion`() = runTest {
        for (access in GalleryAccess.entries) {
            val walk = GalleryDiscovery(ScriptedGallery(access = access, assets = listOf(photo("A")))).discover(policy)
            assertEquals(listOf("A"), walk.candidates.map { it.facts.assetId }, "$access")
            assertEquals(access == GalleryAccess.GRANTED, walk.fullEnumeration, "$access")
        }
    }

    @Test
    fun `an unreadable walk is empty and not authoritative`() = runTest {
        val walk = GalleryDiscovery(ScriptedGallery(readable = false)).discover(policy)
        assertTrue(walk.candidates.isEmpty())
        assertFalse(walk.fullEnumeration, "an empty authoritative walk would delete every in-window row")
    }

    @Test
    fun `keys resolve to exactly their own resources and a departed key to nothing`() = runTest {
        val gallery = ScriptedGallery(assets = listOf(photo("A"), photo("B")))
        val resolved = GalleryDiscovery(gallery).resourcesFor(setOf("A-primary.jpg", "GONE-primary.jpg"))
        assertEquals(listOf("A-primary.jpg"), resolved.map { it.filename })
        assertEquals(listOf("resources([A, GONE])"), gallery.calls, "one request for every key's asset")
    }

    @Test
    fun `resolving nothing asks the gallery nothing and an unreadable gallery resolves nothing`() = runTest {
        val gallery = ScriptedGallery(assets = listOf(photo("A")))
        assertTrue(GalleryDiscovery(gallery).resourcesFor(emptySet()).isEmpty())
        assertTrue(gallery.calls.isEmpty())
        gallery.readable = false
        assertTrue(GalleryDiscovery(gallery).resourcesFor(setOf("A-primary.jpg")).isEmpty())
    }

    // ---- GalleryAssetPresence -----------------------------------------------------------------------

    @Test
    fun `a full grant answers present and absent for every id asked`() = runTest {
        val presence = GalleryAssetPresence(ScriptedGallery(assets = listOf(photo("A"))))
        assertEquals(
            mapOf("A" to AssetPresence.PRESENT, "GONE" to AssetPresence.ABSENT),
            presence.presence(setOf("A", "GONE")),
        )
    }

    @Test
    fun `no other grant may say absent and none asks the gallery`() = runTest {
        for (access in GalleryAccess.entries - GalleryAccess.GRANTED) {
            val gallery = ScriptedGallery(access = access, assets = listOf(photo("A")))
            assertEquals(
                mapOf("A" to AssetPresence.UNKNOWN, "GONE" to AssetPresence.UNKNOWN),
                GalleryAssetPresence(gallery).presence(setOf("A", "GONE")),
                "$access: a partial view's miss is not evidence of absence",
            )
            assertTrue(gallery.calls.isEmpty(), "$access")
        }
    }

    @Test
    fun `an unreadable gallery answers unknown and asking about nothing answers nothing`() = runTest {
        assertEquals(
            mapOf("A" to AssetPresence.UNKNOWN),
            GalleryAssetPresence(ScriptedGallery(readable = false)).presence(setOf("A")),
        )
        val gallery = ScriptedGallery()
        assertEquals(emptyMap(), GalleryAssetPresence(gallery).presence(emptySet()))
        assertTrue(gallery.calls.isEmpty())
    }

    // ---- GalleryAlbums ------------------------------------------------------------------------------

    @Test
    fun `an album is created resolves while it exists and not when unreadable`() = runTest {
        val gallery = ScriptedGallery(albums = listOf(AlbumRecord("album-1", "Trip")))
        val albums = GalleryAlbums(gallery)
        assertEquals("album-new", albums.ensureCreated("Trip"))
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
        albums.add("album-1", listOf("A", "B", "A"))
        gallery.addOutcome = WriteOutcome.Failed("gone")
        albums.add("album-1", listOf("C")) // a failed add is logged, never thrown
        assertEquals(listOf("album-1" to setOf("A", "B"), "album-1" to setOf("C")), gallery.adds)
    }

    @Test
    fun `the denylist reads only the calibration's albums matched trimmed and ignoring case`() = runTest {
        val gallery = ScriptedGallery(
            albums = listOf(
                AlbumRecord("wa", "  whatsapp "),
                AlbumRecord("tg", "TELEGRAM"),
                AlbumRecord("trip", "Signal Hill Hike"),
            ),
            members = mapOf("wa" to setOf("A"), "tg" to setOf("B"), "trip" to setOf("C")),
        )
        val denied = GalleryAlbums(gallery).assetIdsInAlbums(SELECTION_CALIBRATION, captureCutoff("2026-01-01T00:00:00Z"))
        assertEquals(setOf("A", "B"), denied)
        assertEquals(listOf("albums", "albumMembers(wa)", "albumMembers(tg)"), gallery.calls, "O(albums), never O(assets)")
    }

    @Test
    fun `an unreadable gallery denies nothing`() = runTest {
        val cutoff = captureCutoff("2026-01-01T00:00:00Z")
        assertEquals(emptySet(), GalleryAlbums(ScriptedGallery(readable = false)).assetIdsInAlbums(SELECTION_CALIBRATION, cutoff))
        val membersUnreadable = object : GalleryReader by ScriptedGallery(albums = listOf(AlbumRecord("wa", "WhatsApp"))) {
            override suspend fun albumMembers(album: AlbumId, since: CaptureCutoff?): GalleryRead<Set<AssetId>> =
                GalleryRead.NotReadable
        }
        assertEquals(emptySet(), GalleryAlbums(membersUnreadable).assetIdsInAlbums(SELECTION_CALIBRATION, cutoff))
    }
}
