package app.snapsync.domain.invite

import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.MakesEventKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InviteLinkTest {

    private val event = EventId.parse("0f0e8a5c-3f1b-4c2d-9e7a-1b2c3d4e5f60")!!

    @OptIn(MakesEventKey::class)
    @Test
    fun `an invite renders its event and never its key`() {
        val rendered = InviteLink(event, EventKey.of(ByteArray(EventKey.LENGTH) { 7 })).toString()
        assertTrue(event.toString() in rendered, rendered)
        assertFalse("7, 7" in rendered, rendered)
        assertTrue("***" in rendered, rendered)
    }

    @Test
    fun `a link is an invite, an invite whose key is unreadable, or none`() {
        val link = InviteLink(event, key = null)
        assertEquals(link, assertIs<InviteLinkVerdict.Invite>(InviteLinkVerdict.Invite(link)).link)
        assertEquals(event, InviteLinkVerdict.KeyUnreadable(event).event)
        assertIs<InviteLinkVerdict>(InviteLinkVerdict.NotAnInvite)
    }
}
