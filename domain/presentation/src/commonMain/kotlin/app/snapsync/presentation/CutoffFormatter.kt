package app.snapsync.presentation

import app.snapsync.model.CaptureDate
import app.snapsync.model.EVENT_WINDOW_MAX_SECONDS
import app.snapsync.model.localToCutoff
import app.snapsync.model.runCatchingCancellable
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.periodUntil
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Bridges the join screen's **local** date-time picker and the UTC `…Z` capture-date cutoff string
 * (capability `photo-sharing`). Injected into the screen so `:ui:screens` needs no clock or
 * timezone knowledge: it holds only a `LocalDateTime` and calls these methods. **Pure given its
 * inputs** (migration step 9): [now] and [zone] arrive injected — production binds the `Clock` port
 * (`:adapter:generic:app`'s `SystemClock`: its now, and its zone read once, by the host composition)
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
    fun toCutoff(local: LocalDateTime): CaptureDate = localToCutoff(local, zone)

    /** Parse a UTC `…Z` cutoff (the event's `startsAt`) back to a local value for the picker. */
    fun toLocal(cutoff: CaptureDate): LocalDateTime? =
        runCatchingCancellable { Instant.parse(cutoff.iso).toLocalDateTime(zone) }.getOrNull()

    /**
     * "Now" directly as a canonical `…Z` string — the form the event-start comparison needs
     * (`startsAt > nowCutoff()`, capability `sync-status`).
     *
     * Comparing in the **cutoff string domain** rather than converting `startsAt` to a local time and
     * comparing `LocalDateTime`s is deliberate: the strings are fixed-width canonical UTC, so a plain
     * lexicographic `>` IS the chronological answer — the very same property the `creationDate >= cutoff`
     * filter relies on. Round-tripping through local time would introduce a zone and a parse that can
     * fail, for a comparison that needs neither.
     */
    fun nowCutoff(): CaptureDate = toCutoff(nowLocal())

    /**
     * The latest end an event starting at [from] may have (capability `create-event`): [from] plus the
     * backend's event window ([EVENT_WINDOW_MAX_SECONDS], generated from the same deployment value
     * `POST /events` validates against).
     *
     * Measured in INSTANTS, not wall-clock days, because the backend measures `endsAt - startsAt` in seconds
     * between the two UTC values this formatter's [toCutoff] produces: across a daylight-saving change, "30
     * local days later" is an hour more or less than the limit, and the hour more is a refusal.
     */
    fun latestEnd(from: LocalDateTime): LocalDateTime =
        (from.toInstant(zone) + EVENT_WINDOW_MAX_SECONDS.seconds).toLocalDateTime(zone)

    /** Whether `[from, until]` is no longer than the backend's event window — see [latestEnd]. */
    fun fitsEventWindow(from: LocalDateTime, until: LocalDateTime): Boolean =
        until.toInstant(zone) - from.toInstant(zone) <= EVENT_WINDOW_MAX_SECONDS.seconds

    /**
     * The duration between [from] and [until] by its coarsest single unit, for the create screen's live hint
     * (`1 day`, `5 days`, `2 weeks`, `3 hours` — `ui/` words it). The calendar math is done by `kotlinx.datetime`'s
     * [periodUntil] (so month/day lengths are handled by the library, not re-derived here); this only chooses the
     * unit to name.
     */
    fun coarseDuration(from: LocalDateTime, until: LocalDateTime): CoarseDuration {
        val period = from.toInstant(zone).periodUntil(until.toInstant(zone), zone)
        val totalDays = period.years * DAYS_PER_YEAR + period.months * DAYS_PER_MONTH + period.days
        return when {
            totalDays >= 2 * DAYS_PER_WEEK -> CoarseDuration.Weeks(totalDays / DAYS_PER_WEEK)
            totalDays >= 1 -> CoarseDuration.Days(totalDays)
            period.hours >= 1 -> CoarseDuration.Hours(period.hours)
            period.minutes >= 1 -> CoarseDuration.Minutes(period.minutes)
            else -> CoarseDuration.UnderAMinute
        }
    }

    private companion object {
        const val DAYS_PER_WEEK = 7
        const val DAYS_PER_MONTH = 30
        const val DAYS_PER_YEAR = 365
    }
}

/** A duration named by its coarsest whole unit — see [CutoffFormatter.coarseDuration]. */
sealed interface CoarseDuration {
    data class Weeks(val count: Int) : CoarseDuration
    data class Days(val count: Int) : CoarseDuration
    data class Hours(val count: Int) : CoarseDuration
    data class Minutes(val count: Int) : CoarseDuration
    data object UnderAMinute : CoarseDuration
}
