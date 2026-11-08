package app.snapsync.domain.album

import app.snapsync.domain.event.anEvent
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.Share
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.domain.sharing.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A settings Save asks for the album as an opt-in only when it turned the album on. */
class AlbumAskTest {

    private val event = anEvent()

    private fun membership(album: AlbumChoice, direction: Direction = Direction.Both) = Membership.Active(
        event,
        Share(
            assertIs<CaptureRangeVerdict.Ok>(
                CaptureRange.of(
                    CaptureCutoff(event.range.start.at),
                    CaptureCeiling(event.range.end.at),
                    event.range.start,
                    event.range.end,
                ),
            ).range,
            direction,
            album = album,
        ),
    )

    @Test
    fun `a save that turned the album on is an opt-in`() {
        assertEquals(
            AlbumAsk.OptIn,
            AlbumAsk.ofSave(membership(AlbumChoice.LibraryOnly), membership(AlbumChoice.SaveToAlbum)),
        )
    }

    @Test
    fun `a save that left the album on is no opt-in`() {
        val before = membership(AlbumChoice.SaveToAlbum)
        val after = membership(AlbumChoice.SaveToAlbum, direction = Direction.UploadOnly)
        assertEquals(AlbumAsk.Replay, AlbumAsk.ofSave(before, after))
    }

    @Test
    fun `a save that turned the album off or left it off is no opt-in`() {
        assertEquals(
            AlbumAsk.Replay,
            AlbumAsk.ofSave(membership(AlbumChoice.SaveToAlbum), membership(AlbumChoice.LibraryOnly)),
        )
        assertEquals(
            AlbumAsk.Replay,
            AlbumAsk.ofSave(membership(AlbumChoice.LibraryOnly), membership(AlbumChoice.LibraryOnly)),
        )
    }
}
