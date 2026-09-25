package app.snapsync.ports

import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * Wall-clock "now" (`docs/architecture.md`, "Ports are the I/O boundary named for the need"):
 * time is an external system, so nothing in the core — or in `:ui:presentation`, whose
 * `CutoffFormatter` seeds the cutoff picker and the event-start comparison — reads the system clock
 * directly. Adapters implement (`SystemClock` in `:adapter:generic:app`); tests pass a fixed instant.
 */
fun interface Clock {
    fun now(): Instant
}

/**
 * The device's current local time zone — the second external time fact (`docs/architecture.md` names
 * time and timezone separately), consumed wherever a local wall-clock pick converts to the canonical
 * UTC cutoff string (capability `photo-sharing`). Adapters implement (`SystemTimeZone` in
 * `:adapter:generic:app`); tests pass a fixed zone.
 */
fun interface TimeZoneSource {
    fun current(): TimeZone
}
