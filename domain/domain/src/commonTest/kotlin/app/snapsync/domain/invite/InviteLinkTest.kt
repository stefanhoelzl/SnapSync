package app.snapsync.domain.invite

import app.snapsync.domain.event.EventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InviteLinkTest {

    private val event = EventId.parse("0f0e8a5c-3f1b-4c2d-9e7a-1b2c3d4e5f60")!!

    @Test
    fun `an invite renders its event and never its key`() {
        val rendered = InviteLink(event, InviteKey("c2VjcmV0")).toString()
        assertTrue(event.toString() in rendered, rendered)
        assertFalse("c2VjcmV0" in rendered, rendered)
    }

    @Test
    fun `keys are equal by their text`() {
        assertEquals(InviteKey("a"), InviteKey("a"))
        assertEquals(InviteKey("a").hashCode(), InviteKey("a").hashCode())
        assertNotEquals(InviteKey("a"), InviteKey("b"))
        assertNotEquals<Any>(InviteKey("a"), "a")
    }

    @Test
    fun `a link is an invite, an invite whose key is unreadable, or none`() {
        val link = InviteLink(event, key = null)
        assertEquals(link, assertIs<InviteLinkVerdict.Invite>(InviteLinkVerdict.Invite(link)).link)
        assertEquals(event, InviteLinkVerdict.KeyUnreadable(event).event)
        assertIs<InviteLinkVerdict>(InviteLinkVerdict.NotAnInvite)
    }
}
