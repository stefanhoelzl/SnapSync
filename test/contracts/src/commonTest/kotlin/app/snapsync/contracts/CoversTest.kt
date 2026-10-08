package app.snapsync.contracts

import app.snapsync.model.GalleryAccess
import app.snapsync.model.Reply
import app.snapsync.model.SecureStoreRead
import app.snapsync.ports.AttestStore
import app.snapsync.ports.Backend
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.Completion
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import app.snapsync.ports.LibraryChangeTokenRead
import app.snapsync.ports.Lifecycle
import app.snapsync.ports.LifecycleHandlers
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.ports.PhotoGrantRead
import app.snapsync.ports.SecureStore
import app.snapsync.ports.Wake
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
            on<AttestStore>().answers(AttestStore::token).throws()
            on<Backend>().answers(Backend::challenge).withGenericLeaf(Reply.Ok::class)
            on<PhotoGrantRead>().answers(PhotoGrantRead::current).with(GalleryAccess.LIMITED)
            on<PhotoAccessStatusSource>().emits(PhotoAccessStatusSource::permission).with(GalleryAccess.DENIED)
            on<LibraryChangeTokenRead>().answers(LibraryChangeTokenRead::changeToken).with(null)
            on<Lifecycle>().calls(LifecycleHandlers::onForeground)
            on<Download>().calls(DownloadHandlers::onCompleted, String::class, null)
            on<BackgroundTime>().callsBack(BackgroundTime::begin, "onExpiry")
            on<Wake>().handle<Completion>().answers(Completion::complete).returns()
            on<Download> { handle<Completion>().callsBack(Completion::onExpired, "action") }
        }
        assertEquals(
            listOf(
                "SecureStore.read → SecureStoreRead.Unavailable",
                "AttestStore.token → throws",
                "Backend.challenge → Reply.Ok",
                "PhotoGrantRead.current → GalleryAccess.LIMITED",
                "PhotoAccessStatusSource.permission → GalleryAccess.DENIED",
                "LibraryChangeTokenRead.changeToken → null",
                "Lifecycle.handlers.onForeground()",
                "Download.handlers.onCompleted(String, null)",
                "BackgroundTime.begin.onExpiry()",
                "Wake.Completion.complete → returns",
                "Download.Completion.onExpired.action()",
            ),
            covers.cells,
        )
    }

    @Test
    fun `a clause with no declared cell is refused`() {
        assertFailsWith<IllegalArgumentException> { Clause<GalleryAccess, Unit>("C", GalleryAccess.GRANTED, cells { }) { } }
    }
}
