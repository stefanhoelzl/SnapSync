package app.snapsync.domain.membership

import app.snapsync.domain.event.anEvent
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.request.RequestId
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** A member's choices and their requests are values: equal by what they say. */
class ChoicesTest {

    private val event = anEvent()

    @Test
    fun `choices are equal by what was chosen`() {
        val join = JoinChoice(
            event,
            InviteLink(event.id, key = null),
            CaptureCutoff(event.range.start.at),
            CaptureCeiling(event.range.end.at),
            Direction.Both,
            saveToAlbum = true,
        )
        assertEquals(join, join.copy())
        assertNotEquals(join, join.copy(direction = Direction.UploadOnly))

        val share = ShareChoice(event.id, join.from, join.until, Direction.DownloadOnly, saveToAlbum = false)
        assertEquals(share, share.copy())
        assertNotEquals(share, share.copy(saveToAlbum = true))
    }

    @Test
    fun `a request is its id`() {
        assertEquals(RequestId(7), RequestId(7))
        assertNotEquals(RequestId(7), RequestId(8))
        assertEquals(7L, RequestId(7).value)
    }
}
