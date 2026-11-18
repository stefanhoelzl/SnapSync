package app.snapsync.format

import app.snapsync.domain.request.OwnPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The app's own site's pages are pages of the invite link's own origin. */
class OwnPageTextTest {

    @Test
    fun `each page is on the invite link's origin`() {
        assertEquals(LINK_ORIGIN, OwnPageText.url(OwnPage.WEBSITE))
        assertEquals("$LINK_ORIGIN/#privacy", OwnPageText.url(OwnPage.PRIVACY_POLICY))
    }

    @Test
    fun `the site's address is its origin as a person types it`() {
        val address = OwnPageText.address()
        assertEquals(LINK_ORIGIN, "${LINK_ORIGIN.substringBefore("://")}://$address")
        assertTrue("://" !in address, "no scheme in $address")
    }
}
