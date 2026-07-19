package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The event-link filter over a delivered `NSUserActivity` (capability `event-link`; migration step
 * 12). The Swift shell forwards every delivered activity whole; only a browsing-web activity with a
 * URL is an event-link candidate, and the URL passes through **verbatim** — the fragment carries the
 * whole payload, so any trimming here would empty every invite.
 */
class UniversalLinkActivityTest {

    private val link = "https://snapsync.stho.net/join#v=3&d=abc"

    @Test
    fun `a browsing-web activity yields its complete url`() {
        assertEquals(link, eventLinkFromUserActivity(BROWSING_WEB_ACTIVITY_TYPE, link))
    }

    @Test
    fun `the pinned activity type is the ABI string`() {
        // The constant's VALUE is what iOS stamps on a Universal-Link delivery; drifting it would
        // silently drop every invite (the 2026-07-16 failure class).
        assertEquals("NSUserActivityTypeBrowsingWeb", BROWSING_WEB_ACTIVITY_TYPE)
    }

    @Test
    fun `a non-browsing-web activity is filtered`() {
        assertNull(eventLinkFromUserActivity("com.apple.corespotlightitem", link))
    }

    @Test
    fun `a null activity type is filtered`() {
        assertNull(eventLinkFromUserActivity(null, link))
    }

    @Test
    fun `a browsing-web activity without a url is null`() {
        assertNull(eventLinkFromUserActivity(BROWSING_WEB_ACTIVITY_TYPE, null))
    }

    @Test
    fun `forward dispatches the complete url for an event-link candidate`() {
        val forwarded = mutableListOf<String>()
        forwardEventLink(BROWSING_WEB_ACTIVITY_TYPE, link, forwarded::add)
        assertEquals(listOf(link), forwarded)
    }

    @Test
    fun `forward stays silent for a filtered activity`() {
        val forwarded = mutableListOf<String>()
        forwardEventLink("com.apple.handoff", link, forwarded::add)
        forwardEventLink(BROWSING_WEB_ACTIVITY_TYPE, null, forwarded::add)
        assertEquals(emptyList(), forwarded)
    }
}
