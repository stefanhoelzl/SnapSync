package app.snapsync.data.receiving

import app.snapsync.data.membership.event
import app.snapsync.data.sharing.Library
import app.snapsync.domain.receiving.AssetPresence
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.receiving.ReceivedPhotoName
import app.snapsync.domain.receiving.RecognitionWindow
import app.snapsync.mock.LibraryAssets
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ResourceKind
import app.snapsync.testids.testAssetId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The library's SnapSync-marked photos (capability `receiving-photos`): what a recognition pass reads, by the token
 * each one's name carries — narrowed by the pass's window alone, never read for an asset the store records, and read
 * the way the grant says: a walk and ONE batched resource read under a full grant, the held selection under a partial
 * one, nothing at all under none.
 */
class MarkedPhotosTest {
    private val anna = ref("A")
    private val ben = ref("B", BEN)

    /** A received photo in the library: its still named with [ref]'s mark. */
    private fun received(id: String, ref: AssetRef, created: String = "2026-07-15T10:00:00Z") = LibraryAssets.photo(
        id,
        created,
        resources = listOf(
            LibraryAssets.Resource(ResourceKind.PAIRED_VIDEO, "video/quicktime", "IMG.MOV"),
            LibraryAssets.primaryResource(ReceivedPhotoName.mark("IMG_1.HEIC", "x-primary.heic", ref)),
        ),
    )

    private val window = RecognitionWindow.of(event().range, emptyList())

    @Test
    fun `a full grant reads the window's marked photos in one resource read, skipping what the store records`() =
        runTest {
            val library = Library(
                received("L-A", anna),
                received("L-B", ben),
                received("L-LATE", ref("LATE"), created = "2026-09-01T10:00:00Z"),
                LibraryAssets.photo("PLAIN", "2026-07-15T10:00:00Z"),
                LibraryAssets.photo(
                    "VIDEO",
                    "2026-07-15T10:00:00Z",
                    resources = listOf(LibraryAssets.Resource(ResourceKind.VIDEO, "video/mp4", "V.MP4")),
                    isVideo = true,
                ),
            )
            val marked = MarkedPhotos(library.reader, library.selection)

            val found = assertIs<MarkedRead.Found>(marked.inWindow(window, known = setOf(testAssetId("L-B"))))

            assertEquals(mapOf(ReceivedPhotoName.token(anna) to testAssetId("L-A")), found.byToken)
            assertEquals(1, library.calls.count { it.startsWith("resources(") })
            assertEquals(false, library.calls.last().contains("L-B"), "a known asset is never read")
        }

    @Test
    fun `nothing in the window reads no resource, and a library or resources it cannot read find no mark`() = runTest {
        val library = Library()
        val marked = MarkedPhotos(library.reader, library.selection)
        assertEquals(emptyMap(), assertIs<MarkedRead.Found>(marked.inWindow(window, emptySet())).byToken)
        assertEquals(false, library.calls.any { it.startsWith("resources(") })

        library.assetsReadable = false
        assertSame(MarkedRead.NotReadable, marked.inWindow(window, emptySet()))

        val noResources = Library(received("L-A", anna))
        val blind = object : GalleryReader by noResources.reader {
            override suspend fun resources(ids: Set<PlatformAssetId>) = GalleryRead.NotReadable
        }
        assertEquals(
            emptyMap(),
            assertIs<MarkedRead.Found>(MarkedPhotos(blind, noResources.selection).inWindow(window, emptySet())).byToken,
            "a miss downloads it again, never harms",
        )
    }

    @Test
    fun `the reader may answer more than the window, and only the window counts`() = runTest {
        val library = Library(received("L-A", anna), received("L-OUT", ben, created = "2026-01-01T00:00:00Z"))
        val wide = object : GalleryReader by library.reader {
            override suspend fun assets(filter: app.snapsync.ports.AssetFilter): GalleryRead<List<GalleryAsset>> =
                library.gallery.assets(app.snapsync.ports.AssetFilter(null, null))
        }
        val found = assertIs<MarkedRead.Found>(MarkedPhotos(wide, library.selection).inWindow(window, emptySet()))
        assertEquals(setOf(testAssetId("L-A")), found.byToken.values.toSet())
    }

    @Test
    fun `a partial grant reads the held selection, never the library, and an unread one finds nothing`() = runTest {
        val library = Library(grant = LibraryGrant.LIMITED)
        val marked = MarkedPhotos(library.reader, library.selection)
        assertEquals(emptyMap(), assertIs<MarkedRead.Found>(marked.inWindow(window, emptySet())).byToken)

        library.select(
            received("L-A", anna),
            received("L-LATE", ben, created = "2026-09-01T10:00:00Z"),
            LibraryAssets.photo(
                "NO-STILL",
                "2026-07-15T10:00:00Z",
                resources = listOf(LibraryAssets.Resource(ResourceKind.PAIRED_VIDEO, "video/quicktime", "V.MOV")),
            ),
        )

        val found = assertIs<MarkedRead.Found>(marked.inWindow(window, emptySet()))
        assertEquals(mapOf(ReceivedPhotoName.token(anna) to testAssetId("L-A")), found.byToken)
        assertEquals(emptyList(), library.calls)
        val read = library.selection.read!!
        assertEquals(
            setOf(testAssetId("L-A"), testAssetId("L-LATE")),
            marked.among(read, emptySet()).values.toSet(),
            "a selection change asks whatever the capture date",
        )
        assertEquals(emptyMap(), marked.among(read, setOf(testAssetId("L-A"), testAssetId("L-LATE"))))
    }

    @Test
    fun `no usable grant reads nothing`() = runTest {
        val library = Library(grant = LibraryGrant.DENIED)
        assertSame(MarkedRead.NotReadable, MarkedPhotos(library.reader, library.selection).inWindow(window, emptySet()))
    }

    @Test
    fun `the library says whether an asset exists, as the grant lets it`() = runTest {
        val full = Library(LibraryAssets.photo("HERE"))
        val presence = LibraryPresence(full.reader, full.selection)
        assertEquals(
            mapOf(testAssetId("HERE") to AssetPresence.PRESENT, testAssetId("GONE") to AssetPresence.ABSENT),
            presence.of(setOf(testAssetId("HERE"), testAssetId("GONE"))),
        )
        val blind = object : GalleryReader by full.reader {
            override suspend fun assetsById(ids: Set<PlatformAssetId>) = GalleryRead.NotReadable
        }
        assertEquals(
            mapOf(testAssetId("HERE") to AssetPresence.UNKNOWN),
            LibraryPresence(blind, full.selection).of(setOf(testAssetId("HERE"))),
        )

        val limited = Library(grant = LibraryGrant.LIMITED)
        val partial = LibraryPresence(limited.reader, limited.selection)
        assertEquals(mapOf(testAssetId("X") to AssetPresence.UNKNOWN), partial.of(setOf(testAssetId("X"))), "unread")
        limited.select(LibraryAssets.photo("SEEN"))
        assertEquals(
            mapOf(testAssetId("SEEN") to AssetPresence.PRESENT, testAssetId("X") to AssetPresence.UNKNOWN),
            partial.of(setOf(testAssetId("SEEN"), testAssetId("X"))),
            "a miss in a selection is never absence",
        )
    }
}
