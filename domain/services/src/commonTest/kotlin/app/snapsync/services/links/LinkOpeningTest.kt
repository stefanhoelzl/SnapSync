package app.snapsync.services.links

import app.snapsync.model.EventLinkPayload
import app.snapsync.model.encodeEventUrl
import app.snapsync.ports.LinkDelivery
import co.touchlab.kermit.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What each delivery the platform opens the app with becomes: a link to open, or nothing. */
class LinkOpeningTest {

    private val opening = LinkOpening(Logger.withTag("test"))

    @Test
    fun `a link is opened whole - its fragment intact`() {
        val url = "https://snapsync.stho.net/join#v=3&d=payload"
        assertEquals(url, opening.urlIn(LinkDelivery.Link("onLaunchActivity", url)))
    }

    @Test
    fun `an install referrer that carries an invite opens it - and one that carries none opens nothing`() {
        val invite = encodeEventUrl(EventLinkPayload(eventId = "11111111-1111-4111-8111-111111111111"))
        assertEquals(invite, opening.urlIn(LinkDelivery.InstallReferrer("installReferrer", invite.substringAfter('#'))))
        assertNull(
            opening.urlIn(LinkDelivery.InstallReferrer("installReferrer", "utm_source=google-play&utm_medium=organic")),
        )
    }

    @Test
    fun `a delivery that is not a link - or carries no url - opens nothing`() {
        assertNull(opening.urlIn(LinkDelivery.NotALink("onSceneContinueActivity", "x")))
        assertNull(opening.urlIn(LinkDelivery.NotALink("onCreate", null)))
        assertNull(opening.urlIn(LinkDelivery.NoUrl("onLaunchActivity")))
    }
}
