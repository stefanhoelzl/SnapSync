package app.snapsync.model

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * How long the app was away from the foreground: from when it [left] until [now], or `null` on a cold launch, which
 * left from nowhere.
 */
fun awayFor(left: Instant?, now: Instant): Duration? = left?.let { now - it }
