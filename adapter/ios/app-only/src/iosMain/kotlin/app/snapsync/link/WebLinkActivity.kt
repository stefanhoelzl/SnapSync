package app.snapsync.link

import platform.Foundation.NSUserActivityTypeBrowsingWeb

/**
 * Whether a delivered `NSUserActivity` is a **web-link** delivery — the platform-independent fact
 * `model/`'s event-link filter decides on (capability `event-link`).
 *
 * `app-only` by linkage: the extension never handles URLs, and its entitlements declare no
 * associated domain.
 *
 * **Why the constant lives here.** `NSUserActivityTypeBrowsingWeb` is what iOS stamps on every
 * Universal-Link delivery, and it is Apple's. It used to be pinned in `model/` as a string literal so
 * the filter could be exercised off-device — but a `commonTest` could then only assert the literal
 * against a copy of itself, and the core carried an Apple constant to no benefit (spec
 * `module-architecture`). Here the comparison names the real symbol, and its test can fail if Apple
 * ever changes it.
 *
 * The **filter** — the three-way outcome the shell logs — did not move: it is still
 * `eventLinkFromUserActivity` in `model/`, as `architecture-guards` requires. Only the platform
 * comparison did.
 *
 * The shell calls this and hands the result on, so the shell still holds no branch of its own.
 */
fun isWebLinkActivity(activityType: String?): Boolean =
    activityType == NSUserActivityTypeBrowsingWeb
