@file:OptIn(RendersInvite::class, MakesEventKey::class, CompositionOnly::class)

package app.snapsync.data.links

import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.MakesEventKey
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.domain.invite.RendersInvite
import app.snapsync.format.InviteLinkText
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Log
import app.snapsync.inbound.LogLine
import app.snapsync.inbound.LogWriter
import app.snapsync.ports.LinkDelivery
import app.snapsync.testids.testEventId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/** What each delivery the platform opens the app with becomes, read at the boundary: an invite, or nothing. */
class LinkReadingTest {

    private val reading = LinkReading()
    private val event = testEventId("11111111-1111-4111-8111-111111111111")
    private val key = EventKey.of(ByteArray(EventKey.LENGTH) { it.toByte() })!!
    private val lines = mutableListOf<LogLine>()

    @BeforeTest
    fun record() {
        Log.install(
            listOf(object : LogWriter {
                override fun write(line: LogLine) {
                    lines += line
                }
            }),
        )
    }

    @AfterTest
    fun stop() {
        Log.install(emptyList())
    }

    private fun logged(): String = lines.single { it.tag == "LinkReading" }.text()

    @Test
    fun `a link is read whole - its fragment intact`() {
        val url = InviteLinkText.format(InviteLink(event, null)).raw
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
        val invite = InviteLinkText.format(InviteLink(event, null)).raw
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

    @Test
    fun `the line names what a link opened - never its key, whole or cut short`() {
        val invite = InviteLinkText.format(InviteLink(event, key)).raw
        val secret = invite.substringAfterLast('#').substringAfterLast('=')
        listOf(invite, invite.dropLast(4)).forEach { url ->
            lines.clear()
            reading.inviteIn(LinkDelivery.Link("onLaunchActivity", url))
            assertContains(logged(), event.toString())
            assertFalse(secret.dropLast(4) in logged(), "'$url' left its key in '${logged()}'")
        }
    }

    @Test
    fun `the line of a link that is no invite names its length - never its text`() {
        reading.inviteIn(LinkDelivery.Link("onLaunchActivity", "https://example.com/elsewhere"))
        assertEquals("delivered hook=onLaunchActivity from=link of 29 characters opens=no invite", logged())
    }
}
