package app.snapsync.architecture

import app.snapsync.architecture.AcceptedUnclaimed.Kind.MAY
import app.snapsync.architecture.AcceptedUnclaimed.Kind.PLATFORM
import app.snapsync.architecture.AcceptedUnclaimed.Kind.PROCESS

/**
 * **The scenarios left unclaimed on purpose** — what `verifiesReport` leaves out, so the report shows only work still
 * to do. [AcceptedUnclaimedTest] keeps every entry pointing at a scenario that exists and that no test claims.
 *
 * It lives here and never in `openspec/`: the specs are the contract of record, edited only through an OpenSpec
 * change, and why a scenario has no test is engineering, not contract.
 *
 * It is NOT a to-do list. A scenario waiting for a lever, a world event or a screen test stays in the report. And
 * what the api or a web page decides is claimed by the Deno suites, never listed.
 */
internal object AcceptedUnclaimed {

    enum class Kind {
        /** No test of ours can make the platform, a browser or a store do it: the outcome is theirs. */
        PLATFORM,

        /**
         * An accepted gap: the outcome is "may". A test pinning it would turn the gap into a promise (the rule set when
         * the app's specs were claimed).
         */
        MAY,

        /**
         * An obligation on how the product is released, not an outcome anything does at run time: no test can observe
         * it hold. Kept rare; a scenario some code decides is never this.
         */
        PROCESS,
    }

    class Entry(val spec: String, val requirement: String, val scenario: String, val kind: Kind, val reason: String)

    val entries: List<Entry> = listOf(
        // ── MAY: every scenario whose outcome is an accepted "may" ─────────────────────────────────────────────
        Entry(
            "receiving-photos",
            "A deleted received photo never comes back",
            "A reinstall may receive again",
            MAY,
            "a reinstall forgets what was deleted; receiving it again is the accepted gap",
        ),
        Entry(
            "receiving-photos",
            "A deleted received photo never comes back",
            "A reinstall under limited access may receive again",
            MAY,
            "photos outside the selection cannot be recognised after a reinstall; receiving them again is accepted",
        ),
        Entry(
            "receiving-photos",
            "Received photos are never shared back",
            "A reinstall may share received photos back",
            MAY,
            "a photo an unmarking version received cannot be recognised; sharing it back is the accepted gap",
        ),
        Entry(
            "receiving-photos",
            "Received photos are never shared back",
            "A reinstall may share photos received in another event",
            MAY,
            "only the joined event's photos are read; another event's are the accepted gap",
        ),

        // ── PLATFORM: a browser, the OS or a store decides it ─────────────────────────────────────────────────
        Entry(
            "event-site",
            "The download shows its progress and can be retried",
            "Progress while downloading",
            PLATFORM,
            "the progress is drawn by the page's script in a browser; no test of ours runs one",
        ),
        Entry(
            "event-site",
            "The download shows its progress and can be retried",
            "A failed download",
            PLATFORM,
            "a dropped connection and the retry happen in a browser; no test of ours runs one",
        ),
        Entry(
            "event-site",
            "Getting the app does not interrupt a download",
            "The visitor taps Get SnapSync mid-download",
            PLATFORM,
            "the browser decides that a new tab leaves the download running; the link's own tab is pinned",
        ),
        Entry(
            "event-site",
            "Getting the app does not interrupt a download",
            "The visitor follows Google Play mid-download",
            PLATFORM,
            "the browser decides that a new tab leaves the download running; the link's own tab is pinned",
        ),
        Entry(
            "event-site",
            "An empty or unreachable event is explained",
            "Offline visitor",
            PLATFORM,
            "a failed connection is the browser's, and the page's script answers it there",
        ),
        Entry(
            "web-site",
            "A public landing page describes SnapSync",
            "Following the steps installs SnapSync",
            PLATFORM,
            "Google Groups and Google Play decide who may test and what installs",
        ),
        Entry(
            "web-site",
            "The site follows the reader's light or dark mode",
            "A dark-mode reader",
            PLATFORM,
            "the browser applies the colour scheme to the page's styles",
        ),
        Entry(
            "web-site",
            "The site follows the reader's light or dark mode",
            "A light-mode reader",
            PLATFORM,
            "the browser applies the colour scheme to the page's styles",
        ),
        Entry(
            "web-site",
            "The landing page works without JavaScript and by keyboard",
            "A keyboard user browses the screenshots",
            PLATFORM,
            "focus, scrolling and what is announced are the browser's and the screen reader's",
        ),

        Entry(
            "privacy-security",
            "Only a genuine SnapSync app can change an event",
            "A genuine Android phone joins",
            PLATFORM,
            "a real phone's hardware attestation is its maker's; the emulator attests only in software",
        ),
        Entry(
            "privacy-security",
            "Automatic failure reports are minimal and anonymous",
            "The app freezes and the system closes it",
            PLATFORM,
            "Google Play detects and reports a frozen app itself; the app sends nothing for it",
        ),

        // ── PROCESS ───────────────────────────────────────────────────────────────────────────────────────────
        Entry(
            "privacy-security",
            "The Privacy Policy states what leaves the device",
            "A new kind of data starts leaving the device",
            PROCESS,
            "a release obligation: the policy is updated with the release that starts sending; no run shows it",
        ),
        Entry(
            "web-site",
            "The site shows the real app, the same as the App Store",
            "Refreshed captures reach the site",
            PROCESS,
            "a release obligation: the site is published from the committed captures; no run shows a refresh",
        ),
    )
}
