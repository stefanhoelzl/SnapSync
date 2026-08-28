package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The event-link filter over a delivered activity (capability `event-link`; migration step 12). The
 * Swift shell forwards every delivered activity whole; only a web-link activity with a URL is an
 * event-link candidate, and the URL passes through **verbatim** — the fragment carries the whole
 * payload, so any trimming here would empty every invite.
 *
 * Since `absence-is-never-silent` the filter answers with a **named outcome** rather than `String?`
 * (spec `module-architecture`, "Absence is never silent"): the two non-forwarding cases were the
 * silence that made Bugsink `SNAPSYNC-3` undiagnosable, so each is asserted here by name — a
 * regression to a nullable answer cannot compile against these tests.
 *
 * The filter now takes `isWebLink` as a **platform-independent fact** the adapter reports, rather
 * than comparing an Apple constant itself; the constant and its comparison live in
 * `:adapter:ios:app-only`'s `isWebLinkActivity`, and are asserted against the real
 * `NSUserActivityTypeBrowsingWeb` symbol there. The assertion that used to sit here compared a
 * string literal to a copy of itself and could not fail.
 */
class UniversalLinkActivityTest {

    private val link = "https://snapsync.stho.net/join#v=3&d=abc"
    private val browsingWeb = "NSUserActivityTypeBrowsingWeb"

    @Test
    fun `a web-link activity yields its complete url`() {
        assertEquals(
            EventLinkDelivery.Forwarded(link),
            eventLinkFromUserActivity(isWebLink = true, activityType = browsingWeb, url = link),
        )
    }

    @Test
    fun `a non-web-link activity is named rather than dropped`() {
        assertEquals(
            EventLinkDelivery.NotBrowsingWeb("com.apple.corespotlightitem"),
            eventLinkFromUserActivity(isWebLink = false, activityType = "com.apple.corespotlightitem", url = link),
        )
    }

    @Test
    fun `a null activity type is named rather than dropped`() {
        assertEquals(
            EventLinkDelivery.NotBrowsingWeb(null),
            eventLinkFromUserActivity(isWebLink = false, activityType = null, url = link),
        )
    }

    @Test
    fun `the activity type is carried for the log even when it is a web link`() {
        // It is reported, never compared — the comparison is the adapter's. A regression that
        // re-introduced a comparison here would have to re-introduce a constant to compare against.
        val outcome = eventLinkFromUserActivity(isWebLink = false, activityType = browsingWeb, url = link)
        assertEquals(EventLinkDelivery.NotBrowsingWeb(browsingWeb), outcome)
    }

    @Test
    fun `a web-link activity without a url is named rather than dropped`() {
        assertEquals(
            EventLinkDelivery.NoWebpageUrl,
            eventLinkFromUserActivity(isWebLink = true, activityType = browsingWeb, url = null),
        )
    }

    @Test
    fun `forward dispatches the complete url for an event-link candidate`() {
        val forwarded = mutableListOf<String>()
        val outcome = forwardEventLink(true, browsingWeb, link, forwarded::add)
        assertEquals(listOf(link), forwarded)
        assertIs<EventLinkDelivery.Forwarded>(outcome)
    }

    @Test
    fun `forward dispatches nothing for a filtered activity but still reports why`() {
        val forwarded = mutableListOf<String>()
        val handoff = forwardEventLink(false, "com.apple.handoff", link, forwarded::add)
        val urlless = forwardEventLink(true, browsingWeb, null, forwarded::add)
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
