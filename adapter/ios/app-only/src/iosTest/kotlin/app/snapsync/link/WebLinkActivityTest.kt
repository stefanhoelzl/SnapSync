package app.snapsync.link

import platform.Foundation.NSUserActivityTypeBrowsingWeb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The web-link test over a delivered `NSUserActivity` (capability `event-link`).
 *
 * This assertion used to live in `:domain`'s `commonTest` as
 * `assertEquals("NSUserActivityTypeBrowsingWeb", BROWSING_WEB_ACTIVITY_TYPE)` — a constant compared
 * to a copy of itself, which cannot fail and says nothing about iOS. Here it compares against the
 * platform's own symbol, so drifting the value we match on is a red test rather than every invite
 * silently dropping (the 2026-07-16 failure class).
 */
class WebLinkActivityTest {

    @Test
    fun `the platform's browsing-web activity type is what we match on`() {
        assertTrue(isWebLinkActivity(NSUserActivityTypeBrowsingWeb))
    }

    @Test
    fun `the constant's value is still its own name`() {
        // Apple's ABI quirk the filter has always relied on. Asserted against the real symbol, so a
        // change on Apple's side surfaces here instead of on a device.
        assertEquals("NSUserActivityTypeBrowsingWeb", NSUserActivityTypeBrowsingWeb)
    }

    @Test
    fun `every other activity type is not a web link`() {
        assertFalse(isWebLinkActivity("com.apple.corespotlightitem"))
        assertFalse(isWebLinkActivity("com.apple.handoff"))
        assertFalse(isWebLinkActivity(""))
    }

    @Test
    fun `an absent activity type is not a web link`() {
        assertFalse(isWebLinkActivity(null))
    }
}
