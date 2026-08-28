package app.snapsync.model

// The platform constant this filter used to compare against (`NSUserActivityTypeBrowsingWeb`) now
// lives with the platform, in `:adapter:ios:app-only`'s `WebLinkActivity.kt`. The adapter answers the
// platform-independent question — "was this delivery a web link?" — and the filter below decides what
// that means, which is the split the port law asks for (spec `module-architecture`). The filter
// itself is unchanged and still the tested `model/` codec's, as `architecture-guards` requires.

/**
 * What became of a delivered `NSUserActivity` (capability `event-link`; spec `module-architecture`,
 * "Absence is never silent").
 *
 * This used to be `String?`, and that is the defect this type exists to remove: a **three**-state
 * question answered with two states, whose third state was silence. Because the discard wrote
 * nothing, a device log could not distinguish "iOS never called us" from "iOS called us and we threw
 * the activity away" — and on Bugsink `SNAPSYNC-3` that ambiguity WAS the investigation: the only
 * evidence the join gate never opened was the *absence of an unrelated HTTP request*.
 *
 * Every case is therefore nameable, and the entry point logs the one it got. [summary] is what it
 * logs: compact and stable, so a reader greps for the outcome rather than for the absence of a line.
 */
sealed interface EventLinkDelivery {

    /** A compact, log-stable rendering of this outcome. */
    val summary: String

    /** A browsing-web activity carrying a URL: [url] was handed on, fragment intact. */
    data class Forwarded(val url: String) : EventLinkDelivery {
        override val summary: String get() = "forwarded"
    }

    /**
     * Not a Universal Link. iOS hands the scene delegate every restored/continued activity — Handoff,
     * Spotlight, and the browsing-web one this app handles — so this is the ordinary, expected case
     * for everything else. It is recorded rather than dropped so that "we were called" stays
     * distinguishable from "we were never called".
     */
    data class NotBrowsingWeb(val activityType: String?) : EventLinkDelivery {
        override val summary: String get() = "not-browsing-web(type=${activityType ?: "«none»"})"
    }

    /**
     * A browsing-web activity with no `webpageURL`. Not expected from a Universal-Link delivery —
     * which is exactly why it must be visible: if it ever happens it is the difference between a
     * platform that never called and a payload we could not read.
     */
    data object NoWebpageUrl : EventLinkDelivery {
        override val summary: String get() = "no-webpage-url"
    }
}

/**
 * The **enter-line parameters** for a delivered activity: what the platform handed us, recorded
 * before the filter tests any of it (spec `diagnostic-logging`). It lives here rather than at the
 * entry point because the shell may hold no decision at all — even an elvis is one under the
 * complexity gate — and because these strings are part of the diagnostic contract, so they are
 * tested.
 *
 * The URL is reported as present/absent, not verbatim: a forwarded link is logged in full one line
 * later by `onOpenUrl`, and an activity that is *not* forwarded has no URL worth repeating. What
 * matters here is the pair of facts the filter is about to branch on.
 */
fun userActivityParams(activityType: String?, url: String?): String {
    val type = activityType ?: "«none»"
    val webpage = if (url == null) "«absent»" else "present"
    return "type=$type url=$webpage"
}

/**
 * The event-link filter over a delivered activity (capability `event-link`; migration step 12). The
 * shell forwards the platform's answer to "is this a web link?" together with the raw
 * `activityType` and `webpageURL?.absoluteString`, and this decides — the browsing-web test used to
 * be a Swift `guard`, untestable by project rule.
 *
 * [isWebLink] is the platform-independent fact the adapter reports; [activityType] is carried for
 * the **log line only** and is never compared here, so no platform constant is needed to run this.
 *
 * The URL is carried **complete**: the entire payload rides in the fragment, so any trimming here
 * would empty every invite.
 */
fun eventLinkFromUserActivity(
    isWebLink: Boolean,
    activityType: String?,
    url: String?,
): EventLinkDelivery = when {
    !isWebLink -> EventLinkDelivery.NotBrowsingWeb(activityType)
    url == null -> EventLinkDelivery.NoWebpageUrl
    else -> EventLinkDelivery.Forwarded(url)
}

/**
 * [eventLinkFromUserActivity] in continuation-passing form: invoke [forward] with the complete URL
 * iff the activity is an event-link candidate, and **return the outcome either way** so the entry
 * point can name it on its exit line. Exists so the shell's entry stays a straight line — the
 * filter-and-dispatch branch is THIS tested function's, not the wiring's (the detekt shell gate
 * counts even a `?.let` as a decision, and it is one).
 */
fun forwardEventLink(
    isWebLink: Boolean,
    activityType: String?,
    url: String?,
    forward: (String) -> Unit,
): EventLinkDelivery = eventLinkFromUserActivity(isWebLink, activityType, url)
    .also { outcome -> if (outcome is EventLinkDelivery.Forwarded) forward(outcome.url) }
