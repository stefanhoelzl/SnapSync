package app.snapsync.presentation

import app.snapsync.model.localToCutoff
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.periodUntil
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Bridges the join screen's **local** date-time picker and the UTC `…Z` capture-date cutoff string
 * (capability `photo-selection-policy`). Injected into the screen so `:ui:screens` needs no clock or
 * timezone knowledge: it holds only a `LocalDateTime` and calls these methods. **Pure given its
 * inputs** (migration step 9): [now] and [zone] arrive injected — production binds the `Clock` /
 * `TimeZoneSource` ports (`:adapter:generic:app`'s `SystemClock`/`SystemTimeZone`, wired in the shells
 * as plain function/value inputs, since the armed presentation gate forbids this module naming
 * `ports/`); tests pass a fixed instant and zone for determinism. Reuses the config cutoff codec
 * ([localToCutoff]) so the string it produces is byte-identical to the enumerator's `creationDate`
 * shape (the lexicographic-compare invariant).
 */
class CutoffFormatter(
    private val now: () -> Instant,
    private val zone: TimeZone,
) {
    /** The current instant as a local wall-clock value — seeds the "Now" preset and the create screen. */
    fun nowLocal(): LocalDateTime = now().toLocalDateTime(zone)

    /** Convert a picked local wall-clock value to the UTC `yyyy-MM-dd'T'HH:mm:ss'Z'` cutoff string. */
    fun toCutoff(local: LocalDateTime): String = localToCutoff(local, zone)

    /** Parse a UTC `…Z` cutoff (the event's `startsAt`) back to a local value for the picker. */
    fun toLocal(cutoff: String): LocalDateTime? =
        runCatching { Instant.parse(cutoff).toLocalDateTime(zone) }.getOrNull()

    /**
     * "Now" directly as a canonical `…Z` string — the form the event-start comparison needs
     * (`startsAt > nowCutoff()`, capability `sync-status-screen`).
     *
     * Comparing in the **cutoff string domain** rather than converting `startsAt` to a local time and
     * comparing `LocalDateTime`s is deliberate: the strings are fixed-width canonical UTC, so a plain
     * lexicographic `>` IS the chronological answer — the very same property the `creationDate >= cutoff`
     * filter relies on. Round-tripping through local time would introduce a zone and a parse that can
     * fail, for a comparison that needs neither.
     */
    fun nowCutoff(): String = toCutoff(nowLocal())

    /**
     * A **compact adaptive** rendering of a chosen capture-date range `[from, until]` for the join /
     * reconfigure surfaces (capability `photo-selection-policy`). Collapses to the shortest unambiguous
     * form:
     * - same calendar day → `14 Jul, 18:00–23:00`
     * - multi-day, both ends at midnight (whole days) → `14–21 Jul 2026`
     * - otherwise → `14 Jul 18:00 – 21 Jul 23:00`
     */
    fun formatRange(from: LocalDateTime, until: LocalDateTime): String {
        val sameDay = from.year == until.year && from.month == until.month && from.day == until.day
        if (sameDay) return "${dayMon(from)}, ${hhmm(from)}–${hhmm(until)}"
        val wholeDays = from.hour == 0 && from.minute == 0 && from.second == 0 &&
            until.hour == 0 && until.minute == 0 && until.second == 0
        if (wholeDays) {
            return if (from.month == until.month && from.year == until.year) {
                "${from.day}–${until.day} ${mon(until)} ${until.year}"
            } else {
                "${dayMon(from)} – ${dayMon(until)} ${until.year}"
            }
        }
        return "${dayMon(from)} ${hhmm(from)} – ${dayMon(until)} ${hhmm(until)}"
    }

    /**
     * A **humanized** duration between [from] and [until] for the create screen's live hint, e.g.
     * `1 day`, `5 days`, `2 weeks`, `3 hours`. The calendar math is done by `kotlinx.datetime`'s
     * [periodUntil] (so month/day lengths are handled by the library, not re-derived here); this only
     * chooses the coarsest single unit to name.
     */
    fun humanizedDuration(from: LocalDateTime, until: LocalDateTime): String {
        val period = from.toInstant(zone).periodUntil(until.toInstant(zone), zone)
        val totalDays = period.years * 365 + period.months * 30 + period.days
        return when {
            totalDays >= 14 -> plural(totalDays / 7, "week")
            totalDays >= 1 -> plural(totalDays, "day")
            period.hours >= 1 -> plural(period.hours, "hour")
            period.minutes >= 1 -> plural(period.minutes, "minute")
            else -> "less than a minute"
        }
    }

    private fun plural(n: Int, unit: String) = "$n $unit${if (n == 1) "" else "s"}"
    private fun p2(n: Int) = n.toString().padStart(2, '0')
    private fun hhmm(d: LocalDateTime) = "${p2(d.hour)}:${p2(d.minute)}"
    private fun mon(d: LocalDateTime) = MONTHS[d.month.ordinal]
    private fun dayMon(d: LocalDateTime) = "${d.day} ${mon(d)}"

    private companion object {
        val MONTHS = arrayOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        )
    }
}
