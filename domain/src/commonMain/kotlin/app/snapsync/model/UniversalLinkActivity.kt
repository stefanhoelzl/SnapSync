package app.snapsync.model

/**
 * The stable string value of `NSUserActivityTypeBrowsingWeb` (Apple ABI: the constant's value is its
 * own name, and it is what iOS stamps on every Universal-Link delivery). Pinned here so the filter
 * below is testable off-device; the Swift shell forwards the raw `NSUserActivity` fields and holds
 * no constant of its own.
 */
const val BROWSING_WEB_ACTIVITY_TYPE: String = "NSUserActivityTypeBrowsingWeb"

/**
 * The event-link filter over a delivered `NSUserActivity` (capability `event-link`; migration step
 * 12). iOS hands the scene delegate every restored/continued activity — Handoff, Spotlight, and the
 * Universal Link this app actually handles — so the browsing-web filter that used to be a Swift
 * `guard` (untestable by project rule) lives here instead: the shell forwards `activityType` and
 * `webpageURL?.absoluteString` raw, and this decides.
 *
 * Returns the **complete** URL string (the fragment carries the entire payload — capability
 * `event-link`), or `null` for any non-browsing-web activity or one without a URL.
 */
fun eventLinkFromUserActivity(activityType: String?, url: String?): String? =
    if (activityType == BROWSING_WEB_ACTIVITY_TYPE) url else null

/**
 * [eventLinkFromUserActivity] in continuation-passing form: invoke [forward] with the complete URL
 * iff the activity is an event-link candidate. Exists so the shell's `onUserActivity` stays a
 * straight line — the filter-and-dispatch branch is THIS tested function's, not the wiring's (the
 * detekt shell gate counts even a `?.let` as a decision, and it is one).
 */
fun forwardEventLink(activityType: String?, url: String?, forward: (String) -> Unit) {
    eventLinkFromUserActivity(activityType, url)?.let(forward)
}
