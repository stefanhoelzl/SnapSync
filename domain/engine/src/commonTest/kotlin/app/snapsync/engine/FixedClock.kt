package app.snapsync.engine

import kotlin.time.Clock
import kotlin.time.Instant

/** Test clock pinned to [instant]; advance by reassigning it. */
class FixedClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant
}
