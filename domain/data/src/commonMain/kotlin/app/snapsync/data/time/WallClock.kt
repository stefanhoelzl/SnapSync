package app.snapsync.data.time

import app.snapsync.domain.time.Timestamp
import app.snapsync.ports.Clock
import kotlin.time.Instant

/** The device's wall clock, as the domain reads time: a timestamp, to the second. */
class WallClock(private val clock: Clock) {
    fun now(): Timestamp = Timestamp.of(clock.now())

    /** The instant itself, to the platform's precision — what a screen's clock reads between the seconds. */
    fun instant(): Instant = clock.now()
}
