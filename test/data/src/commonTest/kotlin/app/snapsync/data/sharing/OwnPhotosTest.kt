package app.snapsync.data.sharing

import app.snapsync.data.membership.event
import app.snapsync.data.membership.share
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.sharing.DiscoverySource
import app.snapsync.domain.sharing.WalkBounds
import app.snapsync.mock.LibraryAssets
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Instant

/**
 * The member's own photos, the one place their contribution is decided: the grant picks the read before anything is
 * read — a full grant walks the library, a partial one reads the selection in hand and never walks, and anything else
 * reads nothing — the album denylist is read only for a walk of a membership that shares, and the policy admits what
 * the read returned. Observed at the gallery port, because what must NOT be asked is as much the point as the answer.
 */
class OwnPhotosTest {

    private val photo = LibraryAssets.photo("A", DURING)
    private val other = LibraryAssets.photo("B", DURING)
    private val screenshot = LibraryAssets.screenshot("S", DURING)
    private val received = LibraryAssets.lowResPhoto("L", DURING)
    private val messaged = LibraryAssets.photo("W", DURING)

    private fun library(grant: LibraryGrant = LibraryGrant.GRANTED) =
        Library(photo, other, screenshot, received, messaged, grant = grant, albums = mapOf("WhatsApp" to setOf("W")))

    @Test
    fun `a full grant walks the library and judges every photo, its denied albums among the rules`() = runTest {
        val library = library()
        val read = assertIs<DiscoveryRead.Discovered>(library.own.discover(library.contribution()))

        assertEquals(
            ids("A", "B"),
            read.admittedIds(),
            "the screenshot, the low-res copy and the WhatsApp photo are not",
        )
        assertEquals(DiscoverySource.LIBRARY, read.discovery.source)
        assertEquals(5, read.discovery.considered)
        assertEquals(at(NOW), read.discovery.takenAt)
        assertEquals(1, library.walks)
        assertEquals(listOf("albums", "albumMembers(WhatsApp from 2026-07-14T18:00:00Z)"), library.albumReads())
    }

    @Test
    fun `a walk's admitted resources are one request for those alone`() = runTest {
        val library = library()
        val read = assertIs<DiscoveryRead.Discovered>(library.own.discover(library.contribution()))

        assertEquals(listOf("A", "B"), read.resourcesOf(read.discovery.admitted).resolvedIds())
        assertEquals("resources([A, B])", library.calls.last())
    }

    @Test
    fun `a partial grant's read selection is the gallery - never walked, no album read, its screenshot admitted`() =
        runTest {
            val library = library(LibraryGrant.LIMITED)
            library.select(photo, screenshot)
            library.clock.now = Instant.parse("2026-07-16T08:00:00Z")
            val read = assertIs<DiscoveryRead.Discovered>(library.own.discover(library.contribution()))

            assertEquals(ids("A", "S"), read.admittedIds(), "the member chose each one")
            assertEquals(DiscoverySource.SELECTION, read.discovery.source)
            assertEquals(at("2026-07-16T08:00:00Z"), read.discovery.takenAt, "the selection in hand, as of now")
            assertEquals(listOf("A", "S"), read.resourcesOf(read.discovery.admitted).resolvedIds())
            assertEquals(0, library.walks)
            assertEquals(emptyList(), library.calls, "the selection carries its resources: the gallery is never asked")
        }

    @Test
    fun `no readable selection and no usable grant read nothing and state no admitted set`() = runTest {
        val unread = library(LibraryGrant.LIMITED)
        assertSame(
            DiscoveryRead.NotReadable,
            unread.own.discover(unread.contribution()),
            "an unread selection is not empty",
        )
        for (grant in listOf(LibraryGrant.DENIED, LibraryGrant.NOT_DETERMINED)) {
            val library = library(grant)
            library.select(photo)
            assertSame(DiscoveryRead.NotReadable, library.own.discover(library.contribution()), "$grant")
            assertEquals(0, library.walks, "$grant")
            assertEquals(emptyList(), library.calls, "$grant: not even the albums, whose fetch would raise the dialog")
        }
        assertEquals(0, unread.walks)
        assertEquals(emptyList(), unread.calls)
    }

    @Test
    fun `a membership that shares nothing reads neither its albums nor the library`() = runTest {
        val library = library()
        val read = assertIs<DiscoveryRead.Discovered>(
            library.own.discover(library.contribution(Direction.DownloadOnly)),
        )

        assertEquals(emptyList(), read.admittedIds())
        assertEquals(1, library.walks)
        assertEquals(emptyList(), library.calls, "null bounds walk nothing, and no album is read")
    }

    @Test
    fun `an unreadable walk is no discovery`() = runTest {
        val library = library()
        library.assetsReadable = false
        assertSame(DiscoveryRead.NotReadable, library.own.discover(library.contribution()))
    }

    @Test
    fun `a membership shares from its event's start while that lies ahead, and its whole range once it passed`() = runTest {
        val library = Library(LibraryAssets.photo("EARLY", BEFORE))
        val membership = event().let { Membership.Active(it, share(it, from = "2026-07-10T00:00:00Z")) }

        library.clock.now = Instant.parse("2026-07-12T12:00:00Z")
        val waiting = library.own.contribution(membership, received = emptySet())
        val waitingFrom = assertIs<WalkBounds.Within>(waiting.policy.walkBounds).range.from
        assertEquals(CaptureCutoff(at("2026-07-14T18:00:00Z")), waitingFrom)
        assertEquals(emptyList(), assertIs<DiscoveryRead.Discovered>(library.own.discover(waiting)).admittedIds())

        library.clock.now = Instant.parse(NOW)
        val started = library.own.contribution(membership, received = emptySet())
        val startedFrom = assertIs<WalkBounds.Within>(started.policy.walkBounds).range.from
        assertEquals(CaptureCutoff(at("2026-07-10T00:00:00Z")), startedFrom)
        assertEquals(ids("EARLY"), assertIs<DiscoveryRead.Discovered>(library.own.discover(started)).admittedIds())
    }

    @Test
    fun `the preview counts what the range will share, photos waiting for the start among them`() = runTest {
        val library = library()
        library.photos.value += LibraryAssets.photo("EARLY", BEFORE)
        library.clock.now = Instant.parse("2026-07-12T12:00:00Z")

        val counted = library.own.preview(range(from = "2026-07-10T00:00:00Z"), received = emptySet())
        assertEquals(3, assertIs<PreviewRead.Counted>(counted).photos, "A, B and EARLY, though the event lies ahead")

        val echoed = library.own.preview(range(from = "2026-07-10T00:00:00Z"), received = ids("B").toSet())
        assertEquals(2, assertIs<PreviewRead.Counted>(echoed).photos, "a photo this device received is not its own")

        val denied = library(LibraryGrant.DENIED)
        assertSame(PreviewRead.NotReadable, denied.own.preview(range(), received = emptySet()))
    }

    @Test
    fun `a full grant resolves from the gallery, by id`() = runTest {
        val library = library()
        assertEquals(listOf("A"), library.own.resolve(ids("A", "GONE").toSet()).resolvedIds())
        assertEquals(listOf("resources([A, GONE])"), library.calls)
    }

    @Test
    fun `a partial grant resolves from the selection alone, and without one nothing resolves`() = runTest {
        val library = library(LibraryGrant.LIMITED)
        assertSame(
            GalleryRead.NotReadable,
            library.own.resolve(ids("A").toSet()),
            "an unread selection resolves nothing",
        )

        library.select(photo)
        assertEquals(
            listOf("A"),
            library.own.resolve(ids("A", "B").toSet()).resolvedIds(),
            "B is not this app's to upload",
        )
        assertEquals(emptyList(), library.calls)

        library.grant.value = LibraryGrant.DENIED
        assertSame(GalleryRead.NotReadable, library.own.resolve(ids("A").toSet()))
    }
}
