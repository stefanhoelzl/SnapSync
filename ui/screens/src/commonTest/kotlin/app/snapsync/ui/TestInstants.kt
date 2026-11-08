package app.snapsync.ui

import app.snapsync.query.time.CurrentInstant
import kotlin.time.Instant

/** The current instant a test fixes: [read] at every ask. */
fun instants(read: () -> Instant): CurrentInstant = object : CurrentInstant {
    override fun now(): Instant = read()
}
