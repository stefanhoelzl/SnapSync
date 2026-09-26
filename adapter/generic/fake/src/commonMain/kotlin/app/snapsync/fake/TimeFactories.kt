package app.snapsync.fake

import app.snapsync.ports.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/** A [Clock] stopped at [now], in [zone] — the time port's double. Its own file because `Factories.kt` is full. */
fun fixedClock(now: Instant, zone: TimeZone = TimeZone.UTC): Clock = object : Clock {
    override fun now(): Instant = now
    override fun timeZone(): TimeZone = zone
}
