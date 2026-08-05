package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The event-link filter over a delivered `NSUserActivity` (capability `event-link`; migration step
 * 12). The Swift shell forwards every delivered activity whole; only a browsing-web activity with a
 * URL is an event-link candidate, and the URL passes through **verbatim** — the fragment carries the
 * whole payload, so any trimming here would empty every invite.
 *
 * Since `absence-is-never-silent` the filter answers with a **named outcome** rather than `String?`
 * (spec `module-architecture`, "Absence is never silent"): the two non-forwarding cases were the
 * silence that made Bugsink `SNAPSYNC-3` undiagnosable, so each is asserted here by name — a
 * regression to a nullable answer cannot compile against these tests.
 */
class UniversalLinkActivityTest {

    private val link = "https://snapsync.stho.net/join#v=3&d=abc"

    @Test
    fun `a browsing-web activity yields its complete url`() {
        assertEquals(EventLinkDelivery.Forwarded(link), eventLinkFromUserActivity(BROWSING_WEB_ACTIVITY_TYPE, link))
    }

    @Test
    fun `the pinned activity type is the ABI string`() {
        // The constant's VALUE is what iOS stamps on a Universal-Link delivery; drifting it would
        // silently drop every invite (the 2026-07-16 failure class).
        assertEquals("NSUserActivityTypeBrowsingWeb", BROWSING_WEB_ACTIVITY_TYPE)
    }

    @Test
    fun `a non-browsing-web activity is named rather than dropped`() {
        assertEquals(
            EventLinkDelivery.NotBrowsingWeb("com.apple.corespotlightitem"),
            eventLinkFromUserActivity("com.apple.corespotlightitem", link),
        )
    }

    @Test
    fun `a null activity type is named rather than dropped`() {
        assertEquals(EventLinkDelivery.NotBrowsingWeb(null), eventLinkFromUserActivity(null, link))
    }

    @Test
    fun `a browsing-web activity without a url is named rather than dropped`() {
        assertEquals(EventLinkDelivery.NoWebpageUrl, eventLinkFromUserActivity(BROWSING_WEB_ACTIVITY_TYPE, null))
    }

    @Test
    fun `forward dispatches the complete url for an event-link candidate`() {
        val forwarded = mutableListOf<String>()
        val outcome = forwardEventLink(BROWSING_WEB_ACTIVITY_TYPE, link, forwarded::add)
        assertEquals(listOf(link), forwarded)
        assertIs<EventLinkDelivery.Forwarded>(outcome)
    }

    @Test
    fun `forward dispatches nothing for a filtered activity but still reports why`() {
        val forwarded = mutableListOf<String>()
        val handoff = forwardEventLink("com.apple.handoff", link, forwarded::add)
        val urlless = forwardEventLink(BROWSING_WEB_ACTIVITY_TYPE, null, forwarded::add)
        assertEquals(emptyList(), forwarded)
        // The point of the change: a discard is an ANSWER, not the absence of one.
        assertEquals(EventLinkDelivery.NotBrowsingWeb("com.apple.handoff"), handoff)
        assertEquals(EventLinkDelivery.NoWebpageUrl, urlless)
    }

    @Test
    fun `every outcome renders a compact log summary`() {
        // The summaries are what an entry point's exit line carries, so they are part of the
        // diagnostic contract rather than incidental formatting.
        assertEquals("forwarded", EventLinkDelivery.Forwarded(link).summary)
        assertEquals(
            "not-browsing-web(type=com.apple.handoff)",
            EventLinkDelivery.NotBrowsingWeb("com.apple.handoff").summary,
        )
        assertEquals("not-browsing-web(type=«none»)", EventLinkDelivery.NotBrowsingWeb(null).summary)
        assertEquals("no-webpage-url", EventLinkDelivery.NoWebpageUrl.summary)
    }
}
