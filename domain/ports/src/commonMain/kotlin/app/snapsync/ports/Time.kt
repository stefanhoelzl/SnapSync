package app.snapsync.ports

import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * Wall-clock time and the device's local zone — ONE external system, the device's clock settings
 * (`docs/architecture.md`, "Ports are the I/O boundary named for the need"): nothing in the core — or in
 * `:domain:presentation`, whose `CutoffFormatter` seeds the cutoff picker and the event-start comparison — reads
 * the system clock or zone directly. One per process, in `ProcessPorts`.
 *
 * Adapters implement (`SystemClock` in `:adapter:generic:app`); tests pass a fixed instant and zone.
 */
interface Clock {
    /** Now. A wall clock is not monotonic (NTP or the user can step it back), so no caller may rely on that. */
    fun now(): Instant

    /**
     * The device's current local zone — consumed wherever a local wall-clock pick converts to the canonical UTC
     * cutoff string (capability `photo-sharing`). Read once per process by the one place that renders local time.
     */
    fun timeZone(): TimeZone
}
