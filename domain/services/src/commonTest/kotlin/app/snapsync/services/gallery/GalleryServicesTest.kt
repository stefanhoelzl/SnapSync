package app.snapsync.services.gallery

import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.format.TimestampText
import app.snapsync.model.AssetPresence
import app.snapsync.model.CandidateRead
import app.snapsync.model.CandidateScope
import app.snapsync.model.SELECTION_CALIBRATION
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.SelectionRule
import app.snapsync.model.resourcesOf
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.GalleryAlbum
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.LibraryAlbumKind
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.WriteOutcome
import app.snapsync.testids.testAssetId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The gallery services — the decisions made over the thin [GalleryReader]: which answer is authoritative, which grant
 * may say a photo is gone, which albums are denied. The gallery below is a scripted stand-in that records every call,
 * because what these services must NOT ask the gallery is as much the point as what they answer.
 */
class GalleryServicesTest {

    private class ScriptedGallery(
        var access: LibraryGrant = LibraryGrant.GRANTED,
        var readable: Boolean = true,
        var assets: List<GalleryAssetResources> = emptyList(),
        var albums: List<GalleryAlbum> = emptyList(),
        var members: Map<String, Set<PlatformAssetId>> = emptyMap(),
        var created: String? = "album-new",
        var addOutcome: WriteOutcome = WriteOutcome.Ok,
    ) : GalleryReader {
        val calls = mutableListOf<String>()
        val adds = mutableListOf<Pair<String, Set<PlatformAssetId>>>()

        private fun <T> read(name: String, value: () -> T): GalleryRead<T> {
            calls += name
            return if (readable) GalleryRead.Read(value()) else GalleryRead.NotReadable
        }

        override fun access(): LibraryGrant = access
        override suspend fun assets(filter: AssetFilter) = read("assets") { assets.map { it.asset } }
        override suspend fun assetsById(ids: Set<PlatformAssetId>) =
            read("assetsById") { assets.map { it.asset }.filter { it.id in ids } }
        override suspend fun resources(ids: Set<PlatformAssetId>) =
            read("resources(${ids.map { it.value }})") { assets.filter { it.asset.id in ids } }
        override suspend fun albums() = read("albums") { albums }
        override suspend fun albumsById(
            ids: Set<PlatformAlbumId>,
        ) = read("albumsById") { albums.filter { it.id in ids } }
        override suspend fun albumMembers(album: PlatformAlbumId, createdFrom: Instant?) =
            read("albumMembers(${album.value})") { members[album.value].orEmpty() }
        override val albumKind: LibraryAlbumKind = LibraryAlbumKind.COLLECTION
        override suspend fun createAlbum(title: String): PlatformAlbumId? =
            created?.let(::PlatformAlbumId).also { calls += "createAlbum($title)" }
        override suspend fun addToAlbum(album: PlatformAlbumId, assets: Set<PlatformAssetId>): WriteOutcome {
            adds += album.value to assets
            return addOutcome
        }

        override suspend fun export(resource: GalleryResourceHandle, to: String): WriteOutcome =
            WriteOutcome.Failed("no export in this script")
    }

    private fun photo(id: String) = platformPhoto(id)

    private val policy = SelectionPolicy(
        listOf(SelectionRule.CaptureAfter(CaptureCutoff(TimestampText.parse("2026-01-01T00:00:00Z")))),
    )

    // ---- GalleryCandidateSource ---------------------------------------------------------------------

    @Test
    fun `candidates are the gallery’s facts and the admitted ones’ resources come in ONE request`() = runTest {
        val gallery = ScriptedGallery(assets = listOf(photo("A"), photo("B"), photo("C")))
        val read = assertIs<CandidateRead.Readable>(GalleryCandidateSource(gallery).candidates(policy))
        assertEquals(
            listOf(testAssetId("A"), testAssetId("B"), testAssetId("C")),
            read.candidates.map { it.facts.assetId },
        )
        assertEquals(listOf("assets"), gallery.calls, "facts cost no resource read")

        val resources = resourcesOf(read.candidates.filter { it.facts.assetId != testAssetId("B") })

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
        assertEquals(
            CandidateRead.NotReadable,
            GalleryCandidateSource(ScriptedGallery(readable = false)).candidates(policy),
        )
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
    fun `only a full grant’s walk is authoritative for deletion`() = runTest {
        for (access in LibraryGrant.entries) {
            val walk = GalleryDiscovery(ScriptedGallery(access = access, assets = listOf(photo("A")))).discover(policy)
            assertEquals(listOf(testAssetId("A")), walk.candidates.map { it.facts.assetId }, "$access")
            assertEquals(access == LibraryGrant.GRANTED, walk.fullEnumeration, "$access")
        }
    }

    @Test
    fun `a policy that admits nothing reads nothing - its empty walk as authoritative as the grant makes it`() =
        runTest {
            for (access in LibraryGrant.entries) {
                val gallery = ScriptedGallery(access = access, assets = listOf(photo("A")))
                val walk = GalleryDiscovery(gallery).discover(SelectionPolicy(listOf(SelectionRule.DenyAll)))
                assertTrue(walk.candidates.isEmpty(), "$access")
                assertEquals(access == LibraryGrant.GRANTED, walk.fullEnumeration, "$access")
                assertTrue(gallery.calls.isEmpty(), "nothing is read: $access")
            }
        }

    @Test
    fun `a policy that admits nothing is a counted zero where the library is readable and no answer where not`() =
        runTest {
            val denyAll = SelectionPolicy(listOf(SelectionRule.DenyAll))
            for (access in LibraryGrant.entries) {
                val gallery = ScriptedGallery(access = access, assets = listOf(photo("A")))
                val read = GalleryCandidateSource(gallery).candidates(denyAll)
                if (access == LibraryGrant.GRANTED || access == LibraryGrant.LIMITED) {
                    assertEquals(CandidateRead.Readable(emptyList(), CandidateScope.Library), read, "$access")
                } else {
                    assertEquals(CandidateRead.NotReadable, read, "$access")
                }
                assertTrue(gallery.calls.isEmpty(), "nothing is read: $access")
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

    @Test
    fun `the denylist reads only the calibration’s albums matched trimmed and ignoring case`() = runTest {
        val gallery = ScriptedGallery(
            albums = listOf(
                GalleryAlbum(PlatformAlbumId("wa"), "  whatsapp "),
                GalleryAlbum(PlatformAlbumId("tg"), "TELEGRAM"),
                GalleryAlbum(PlatformAlbumId("trip"), "Signal Hill Hike"),
            ),
            members = mapOf(
                "wa" to setOf(PlatformAssetId("A")),
                "tg" to setOf(PlatformAssetId("B")),
                "trip" to setOf(PlatformAssetId("C")),
            ),
        )
        val denied = GalleryAlbums(
            gallery,
        ).assetIdsInAlbums(SELECTION_CALIBRATION, CaptureCutoff(TimestampText.parse("2026-01-01T00:00:00Z")))
        assertEquals(setOf(testAssetId("A"), testAssetId("B")), denied)
        assertEquals(
            listOf("albums", "albumMembers(wa)", "albumMembers(tg)"),
            gallery.calls,
            "O(albums), never O(assets)",
        )
    }

    @Test
    fun `an unreadable gallery denies nothing`() = runTest {
        val cutoff = CaptureCutoff(TimestampText.parse("2026-01-01T00:00:00Z"))
        assertEquals(
            emptySet(),
            GalleryAlbums(ScriptedGallery(readable = false)).assetIdsInAlbums(SELECTION_CALIBRATION, cutoff),
        )
        val membersUnreadable = object : GalleryReader by ScriptedGallery(
            albums = listOf(GalleryAlbum(PlatformAlbumId("wa"), "WhatsApp")),
        ) {
            override suspend fun albumMembers(
                album: PlatformAlbumId,
                createdFrom: Instant?,
            ): GalleryRead<Set<PlatformAssetId>> =
                GalleryRead.NotReadable
        }
        assertEquals(emptySet(), GalleryAlbums(membersUnreadable).assetIdsInAlbums(SELECTION_CALIBRATION, cutoff))
    }
}
