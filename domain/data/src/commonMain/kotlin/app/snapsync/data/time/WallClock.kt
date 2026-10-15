package app.snapsync.data.time

import app.snapsync.domain.time.Timestamp
import app.snapsync.ports.Clock

/** The device's wall clock, as the domain reads time: a timestamp, to the second. */
class WallClock(private val clock: Clock) {
    fun now(): Timestamp = Timestamp.of(clock.now())
}
