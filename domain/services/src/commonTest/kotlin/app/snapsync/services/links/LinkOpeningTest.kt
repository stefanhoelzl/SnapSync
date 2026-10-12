package app.snapsync.services.links

import app.snapsync.model.EntryScope
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.encodeEventUrl
import app.snapsync.ports.LinkDelivery
import co.touchlab.kermit.Logger
import kotlin.test.Test
import kotlin.test.assertEquals

/** What each delivery the platform opens the app with becomes: opened, or named and left. */
class LinkOpeningTest {

    private val opening = LinkOpening(Logger.withTag("test"), EntryScope.None)

    private fun opens(delivery: LinkDelivery): Pair<String, List<String>> {
        val opened = mutableListOf<String>()
        return opening.deliver(delivery) { opened += it } to opened
    }

    @Test
    fun `a link is opened whole - its fragment intact`() {
        val url = "https://snapsync.stho.net/join#v=3&d=payload"
        assertEquals("opened" to listOf(url), opens(LinkDelivery.Link("onLaunchActivity", url)))
    }

    @Test
    fun `an install referrer that carries an invite opens it - and one that carries none opens nothing`() {
        val invite = encodeEventUrl(EventLinkPayload(eventId = "11111111-1111-4111-8111-111111111111"))
        assertEquals(
            "opened" to listOf(invite),
            opens(LinkDelivery.InstallReferrer("installReferrer", invite.substringAfter('#'))),
        )
        assertEquals(
            "no invite" to emptyList<String>(),
            opens(LinkDelivery.InstallReferrer("installReferrer", "utm_source=google-play&utm_medium=organic")),
        )
    }

    @Test
    fun `a delivery that is not a link - or carries no url - opens nothing and is named`() {
        assertEquals("not a link" to emptyList<String>(), opens(LinkDelivery.NotALink("onSceneContinueActivity", "x")))
        assertEquals("not a link" to emptyList<String>(), opens(LinkDelivery.NotALink("onCreate", null)))
        assertEquals("no url" to emptyList<String>(), opens(LinkDelivery.NoUrl("onLaunchActivity")))
    }
}
