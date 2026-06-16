package app.snapsync.s3

import kotlin.time.Clock
import kotlin.time.Instant

/** Test clock pinned to [instant], so presigned output is byte-deterministic. */
class FixedClock(val instant: Instant) : Clock {
    override fun now(): Instant = instant
}
