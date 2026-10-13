package app.snapsync.contracts

import app.snapsync.inbound.Completion
import app.snapsync.inbound.Running
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.CompletedDownload
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.LibraryChangeTokenRead
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.Lifecycle
import app.snapsync.ports.LifecycleHandlers
import app.snapsync.ports.SecureStore
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.Wake
import app.snapsync.wire.api.ChallengeReply
import app.snapsync.wire.api.EventNotFound
import app.snapsync.wire.api.Routes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The renderer writes the port grid's exact text for every kind of cell. Literal expectations, so a host whose class
 * names render differently fails here rather than as a mismatched claim.
 */
class CoversTest {

    @Test
    fun `every kind of cell renders as the grid writes it`() {
        val covers = cells {
            on<SecureStore>().answers(SecureStore::read).with(SecureStoreRead.Unavailable::class)
            backend(Routes.Challenge.ID, ChallengeReply.Issued::class)
            backend(Routes.GetEvent.ID, EventNotFound::class)
            on<GalleryReader>().answers(GalleryReader::access).with(LibraryGrant.LIMITED)
            on<Gallery>().emits(Gallery::grant).with(LibraryGrant.DENIED)
            on<LibraryChangeTokenRead>().answers(LibraryChangeTokenRead::changeToken).with(null)
            on<Lifecycle>().calls(LifecycleHandlers::onForeground)
            on<Download>().calls(DownloadHandlers::onCompleted, CompletedDownload::class)
            on<BackgroundTime>().handle<Running>().answers(Running::expire).returns()
            on<Wake>().handle<Completion>().answers(Completion::complete).returns()
        }
        assertEquals(
            listOf(
                "SecureStore.read → SecureStoreRead.Unavailable",
                "Backend.challenge → Issued",
                "Backend.* → EventNotFound",
                "GalleryReader.access → LibraryGrant.LIMITED",
                "Gallery.grant → LibraryGrant.DENIED",
                "LibraryChangeTokenRead.changeToken → null",
                "Lifecycle.handlers.onForeground()",
                "Download.handlers.onCompleted(CompletedDownload)",
                "BackgroundTime.Running.expire → returns",
                "Wake.Completion.complete → returns",
            ),
            covers.cells,
        )
    }

    @Test
    fun `a clause with no declared cell is refused`() {
        assertFailsWith<IllegalArgumentException> {
            Clause<LibraryGrant, Unit>("C", LibraryGrant.GRANTED, cells { }) { }
        }
    }

    @Test
    fun `a one-of group renders apart from the outright claims`() {
        val covers = cells {
            on<GalleryReader>().answers(GalleryReader::access).with(LibraryGrant.GRANTED)
            oneOf {
                on<GalleryReader>().answers(GalleryReader::access).with(LibraryGrant.NOT_DETERMINED)
                on<GalleryReader>().answers(GalleryReader::access).with(LibraryGrant.DENIED)
            }
        }
        assertEquals(listOf("GalleryReader.access → LibraryGrant.GRANTED"), covers.cells)
        assertEquals(
            listOf(
                listOf(
                    "GalleryReader.access → LibraryGrant.NOT_DETERMINED",
                    "GalleryReader.access → LibraryGrant.DENIED",
                ),
            ),
            covers.oneOf,
        )
    }

    @Test
    fun `a one-of group of one cell is refused`() {
        assertFailsWith<IllegalArgumentException> {
            cells { oneOf { on<GalleryReader>().answers(GalleryReader::access).with(LibraryGrant.DENIED) } }
        }
    }
}
