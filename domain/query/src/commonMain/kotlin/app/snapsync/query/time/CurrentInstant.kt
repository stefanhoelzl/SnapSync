package app.snapsync.query.time

import app.snapsync.data.time.WallClock
import kotlin.time.Instant

/** **The current instant**, as the screen reads it: the device's wall clock, read at every ask. */
interface CurrentInstant {
    fun now(): Instant
}

/** The device's wall clock as the screen reads it — the process's one [WallClock], to the millisecond. */
class WallClockInstant(private val clock: WallClock) : CurrentInstant {
    override fun now(): Instant = clock.instant()
}
