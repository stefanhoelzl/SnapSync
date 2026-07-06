package app.snapsync.config

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Capture-date cutoff string helpers (capability `photo-date-cutoff`).
 *
 * A cutoff is compared against an asset's `creationDate` **lexicographically** (`creationDate >=
 * cutoff`), so it MUST be byte-identical in shape to what the iOS enumerator produces — a bare
 * `NSISO8601DateFormatter()`: UTC `yyyy-MM-dd'T'HH:mm:ss'Z'`, **second** precision, no offset, no
 * fractional seconds. These helpers are the single origin of that shape for "now" and manual picks;
 * a cutoff sourced from an event's fetched `createdAt` is already this shape and is reused verbatim.
 *
 * The [Clock] is injected (DI, not `expect`/`actual`) so "now" is unit-testable in `commonTest`.
 */

/** Format any instant to the exact UTC `yyyy-MM-dd'T'HH:mm:ss'Z'` cutoff shape. */
fun instantToCutoff(instant: Instant): String {
    val dt = instant.toLocalDateTime(TimeZone.UTC)
    return buildCutoff(dt.year, dt.month.ordinal + 1, dt.day, dt.hour, dt.minute, dt.second)
}

/** The "now" cutoff from the injected [clock], in the required UTC `…Z` shape. */
fun nowCutoff(clock: Clock): String = instantToCutoff(clock.now())

/**
 * Convert a picked **local** wall-clock date-time (in the device [zone]) to the UTC `…Z` cutoff,
 * so a manual pick compares correctly against the UTC `creationDate` strings.
 */
fun localToCutoff(local: LocalDateTime, zone: TimeZone): String =
    instantToCutoff(local.toInstant(zone))

private fun buildCutoff(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): String {
    fun p(n: Int, width: Int) = n.toString().padStart(width, '0')
    return "${p(year, 4)}-${p(month, 2)}-${p(day, 2)}T${p(hour, 2)}:${p(minute, 2)}:${p(second, 2)}Z"
}
