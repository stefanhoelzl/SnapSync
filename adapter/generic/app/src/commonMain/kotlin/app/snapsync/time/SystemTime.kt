package app.snapsync.time

import app.snapsync.ports.Clock
import kotlinx.datetime.TimeZone

/** The production [Clock]: the device's system clock and its current default zone. */
object SystemClock : Clock {
    override fun now(): kotlin.time.Instant = kotlin.time.Clock.System.now()
    override fun timeZone(): TimeZone = TimeZone.currentSystemDefault()
}
