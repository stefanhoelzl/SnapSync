package app.snapsync.link

import platform.Foundation.NSUserActivityTypeBrowsingWeb

/**
 * Whether a delivered `NSUserActivity` is a **web-link** delivery — what [IosLinks] tells a link from another
 * activity by.
 *
 * `app-only` by linkage: the extension never handles URLs, and its entitlements declare no
 * associated domain.
 *
 * The constant is Apple's and lives here, with the platform: the core never compares an activity type.
 */
fun isWebLinkActivity(activityType: String?): Boolean =
    activityType == NSUserActivityTypeBrowsingWeb
