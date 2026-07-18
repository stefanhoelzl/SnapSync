package app.snapsync.time

import app.snapsync.ports.Clock
import app.snapsync.ports.TimeZoneSource
import kotlinx.datetime.TimeZone

/** The production [Clock]: the device's system clock. */
object SystemClock : Clock {
    override fun now(): kotlin.time.Instant = kotlin.time.Clock.System.now()
}

/** The production [TimeZoneSource]: the device's current default zone. */
object SystemTimeZone : TimeZoneSource {
    override fun current(): TimeZone = TimeZone.currentSystemDefault()
}
