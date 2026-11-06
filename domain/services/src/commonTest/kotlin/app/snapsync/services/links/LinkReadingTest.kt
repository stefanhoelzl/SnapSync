package app.snapsync.services.links

import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.format.InviteLinkText
import app.snapsync.ports.LinkDelivery
import app.snapsync.testids.testEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/** What each delivery the platform opens the app with becomes, read at the boundary: an invite, or nothing. */
class LinkReadingTest {

    private val reading = LinkReading()
    private val event = testEventId("11111111-1111-4111-8111-111111111111")

    @Test
    fun `a link is read whole - its fragment intact`() {
        val url = InviteLinkText.format(InviteLink(event, null))
        val read = assertIs<InviteLinkVerdict.Invite>(reading.inviteIn(LinkDelivery.Link("onLaunchActivity", url)))
        assertEquals(event, read.link.event)
    }

    @Test
    fun `a link that is no invite is read as none`() {
        val read = reading.inviteIn(LinkDelivery.Link("onLaunchActivity", "https://example.com/elsewhere"))
        assertSame(InviteLinkVerdict.NotAnInvite, read)
    }

    @Test
    fun `an install referrer that carries an invite opens it - and one that carries none opens nothing`() {
        val invite = InviteLinkText.format(InviteLink(event, null))
        val read = reading.inviteIn(LinkDelivery.InstallReferrer("installReferrer", invite.substringAfter('#')))
        assertEquals(event, assertIs<InviteLinkVerdict.Invite>(read).link.event)
        assertNull(
            reading.inviteIn(
                LinkDelivery.InstallReferrer("installReferrer", "utm_source=google-play&utm_medium=organic"),
            ),
        )
    }

    @Test
    fun `a delivery that is not a link - or carries no url - opens nothing`() {
        assertNull(reading.inviteIn(LinkDelivery.NotALink("onSceneContinueActivity", "x")))
        assertNull(reading.inviteIn(LinkDelivery.NotALink("onCreate", null)))
        assertNull(reading.inviteIn(LinkDelivery.NoUrl("onLaunchActivity")))
    }
}
