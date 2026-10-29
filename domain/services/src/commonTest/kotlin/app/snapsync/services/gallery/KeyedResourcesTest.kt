package app.snapsync.services.gallery

import app.snapsync.data.sharing.DenylistedAlbums
import app.snapsync.data.sharing.DiscoveryRead
import app.snapsync.data.sharing.GalleryWalk
import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.data.sharing.SelectionSnapshots
import app.snapsync.data.time.WallClock
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.time.Timestamp
import app.snapsync.ports.Clock
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.ResourceKind
import app.snapsync.ports.SelectionSnapshot
import app.snapsync.testids.testAssetId
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Ledger keys resolved to uploadable resources ([KeyedResources]) and an admitted read's resources
 * ([DiscoveryRead.Discovered.uploadable]): id-scoped, partial-tolerant, and — under a partial grant — only ever from the
 * selection in hand, so a photo outside it is not this app's to upload (capability `photo-sharing`).
 */
class KeyedResourcesTest {

    private val clock = WallClock(
        object : Clock {
            override fun now(): Instant = Instant.parse("2026-06-10T00:00:00Z")
            override fun timeZone(): TimeZone = TimeZone.UTC
        },
    )

    private fun at(text: String) = Timestamp.of(Instant.parse(text))

    /** The member's range: the event's own week, which every [platformPhoto] lies in. */
    private val range: CaptureRange = (
        CaptureRange.of(
            CaptureCutoff(at("2026-06-01T00:00:00Z")),
            CaptureCeiling(at("2026-06-07T00:00:00Z")),
            EventStart(at("2026-06-01T00:00:00Z")),
            EventEnd(at("2026-06-07T00:00:00Z")),
        ) as CaptureRangeVerdict.Ok
        ).range

    private fun ownPhotos(gallery: ScriptedGallery, selection: SelectionSnapshots = SelectionSnapshots()) =
        OwnPhotos(gallery, GalleryWalk(gallery, clock), selection, DenylistedAlbums(gallery), clock)

    private suspend fun selectionOf(vararg ids: String): SelectionSnapshots = SelectionSnapshots().also { snapshots ->
        snapshots.published(flowOf(SelectionSnapshot(ids.map { platformPhoto(it) }))) {}.collect()
    }

    private val livePhoto = platformPhoto(
        "L",
        resources = listOf(ResourceKind.PHOTO to "IMG.HEIC", ResourceKind.PAIRED_VIDEO to "IMG.MOV"),
    )

    /** The filenames a resolve found — which must be [KeyedRead.Resolved]. */
    private fun filenames(read: KeyedRead): List<String> = assertIs<KeyedRead.Resolved>(
        read,
    ).resources.map { it.filename }

    // ---- GalleryKeyedResources ----------------------------------------------------------------------

    @Test
    fun `keys resolve to exactly their own resources and a departed key to nothing`() = runTest {
        val gallery = ScriptedGallery(assets = listOf(platformPhoto("A"), platformPhoto("B")))
        val resolved = GalleryKeyedResources(gallery).resourcesFor(setOf("A-primary.jpg", "GONE-primary.jpg"))
        assertEquals(listOf("A-primary.jpg"), filenames(resolved), "only the asked keys, a departed one absent")
        assertEquals(listOf("resources([A, GONE])"), gallery.calls, "one request for every key's asset")
    }

    @Test
    fun `a still's key never brings its paired video along`() = runTest {
        val gallery = ScriptedGallery(assets = listOf(livePhoto))
        assertEquals(
            listOf("L-primary.heic"),
            filenames(GalleryKeyedResources(gallery).resourcesFor(setOf("L-primary.heic"))),
        )
    }

    @Test
    fun `an unreadable gallery is NotReadable and resolving nothing resolves nothing`() = runTest {
        val gallery = ScriptedGallery(assets = listOf(platformPhoto("A")))
        assertEquals(emptyList(), filenames(GalleryKeyedResources(gallery).resourcesFor(emptySet())))
        gallery.readable = false
        // Could not look is never gone: no resolved-empty answer a caller could read as "the asset departed".
        assertEquals(KeyedRead.NotReadable, GalleryKeyedResources(gallery).resourcesFor(setOf("A-primary.jpg")))
    }

    @Test
    fun `a key's live resource is its own, and none when it departed or nothing could be read`() = runTest {
        val gallery = ScriptedGallery(assets = listOf(platformPhoto("A")))
        val resolved = GalleryKeyedResources(gallery).resourcesFor(setOf("A-primary.jpg", "GONE-primary.jpg"))
        assertEquals("A-primary.jpg", resolved.liveResource("A-primary.jpg")?.filename)
        assertNull(resolved.liveResource("GONE-primary.jpg"), "a departed asset has no live resource")
        assertNull(KeyedRead.NotReadable.liveResource("A-primary.jpg"), "could not look offers none either")
    }

    // ---- ContributionKeyedResources -----------------------------------------------------------------

    @Test
    fun `under a full grant keys resolve from the gallery`() = runTest {
        val gallery = ScriptedGallery(assets = listOf(platformPhoto("A"), livePhoto))
        val resolved = ContributionKeyedResources(ownPhotos(gallery))
            .resourcesFor(setOf("A-primary.jpg", "L-live.mov", "GONE-primary.jpg"))
        assertEquals(listOf("A-primary.jpg", "L-live.mov"), filenames(resolved), "only the asked keys")
        assertEquals(listOf("resources([A, L, GONE])"), gallery.calls)
    }

    @Test
    fun `under a partial grant only the selection in hand resolves and the gallery is never asked`() = runTest {
        val gallery = ScriptedGallery(
            access = LibraryGrant.LIMITED,
            assets = listOf(platformPhoto("IN"), platformPhoto("OUT")),
        )
        val resolved = ContributionKeyedResources(ownPhotos(gallery, selectionOf("IN")))
            .resourcesFor(setOf("IN-primary.jpg", "OUT-primary.jpg"))
        assertEquals(listOf("IN-primary.jpg"), filenames(resolved), "a photo outside it is not ours")
        assertTrue(gallery.calls.isEmpty(), "under a partial grant the selection is the gallery")
    }

    @Test
    fun `with nothing readable the resolve is NotReadable and the gallery is never asked`() = runTest {
        for (access in LibraryGrant.entries - LibraryGrant.GRANTED) {
            // LIMITED with no selection read yet: an unread selection is not an empty one, so nothing is concluded.
            val gallery = ScriptedGallery(access = access, assets = listOf(platformPhoto("A")))
            assertEquals(
                KeyedRead.NotReadable,
                ContributionKeyedResources(ownPhotos(gallery)).resourcesFor(setOf("A-primary.jpg")),
                "$access",
            )
            assertTrue(gallery.calls.isEmpty(), "$access")
        }
    }

    // ---- DiscoveryRead.Discovered.uploadable --------------------------------------------------------

    @Test
    fun `a walk's admitted assets' resources come in ONE request`() = runTest {
        val gallery = ScriptedGallery(assets = listOf(platformPhoto("A"), livePhoto))
        val photos = ownPhotos(gallery)
        val read = assertIs<DiscoveryRead.Discovered>(
            photos.discover(photos.contribution(Direction.UploadOnly, range, emptySet())),
        )
        gallery.calls.clear()

        val resources = read.uploadable(read.discovery.admitted)

        assertEquals(listOf("A-primary.jpg", "L-primary.heic", "L-live.mov"), resources.map { it.filename })
        assertEquals(listOf("resources([A, L])"), gallery.calls, "one batch for every admitted asset")
    }

    @Test
    fun `a gallery that becomes unreadable before the resource read yields no resources rather than wrong ones`() =
        runTest {
            val gallery = ScriptedGallery(assets = listOf(platformPhoto("A")))
            val photos = ownPhotos(gallery)
            val read = assertIs<DiscoveryRead.Discovered>(
                photos.discover(photos.contribution(Direction.UploadOnly, range, emptySet())),
            )
            gallery.readable = false
            assertTrue(read.uploadable(read.discovery.admitted).isEmpty())
        }

    @Test
    fun `a selection's admitted assets' resources are already in hand`() = runTest {
        val gallery = ScriptedGallery(access = LibraryGrant.LIMITED)
        val photos = ownPhotos(gallery, selectionOf("S1", "S2"))
        val read = assertIs<DiscoveryRead.Discovered>(
            photos.discover(photos.contribution(Direction.UploadOnly, range, emptySet())),
        )

        val resources = read.uploadable(read.discovery.admitted.filter { it.facts.assetId != testAssetId("S2") })

        assertEquals(listOf("S1-primary.jpg"), resources.map { it.filename })
        assertTrue(gallery.calls.isEmpty(), "no gallery read under a partial grant")
    }
}
