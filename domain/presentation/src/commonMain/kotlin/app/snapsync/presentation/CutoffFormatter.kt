package app.snapsync.presentation

import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.EVENT_WINDOW_MAX_SECONDS
import app.snapsync.model.EventEnd
import app.snapsync.model.EventStart
import app.snapsync.model.localToCutoff
import app.snapsync.ui.state.CoarseDuration
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.periodUntil
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Bridges the join screen's **local** date-time picker and the capture dates.
 * Injected into the screen so `:ui:screens` needs no clock or
 * timezone knowledge: it holds only a `LocalDateTime` and calls these methods. **Pure given its
 * inputs** (migration step 9): [now] and [zone] arrive injected — production binds the `Clock` port
 * (`:adapter:generic:app`'s `SystemClock`: its now, and its zone read once, by the host composition)
 * as plain function/value inputs, since the armed presentation gate forbids this module naming
 * `ports/`); tests pass a fixed instant and zone for determinism.
 */
class CutoffFormatter(
    private val now: () -> Instant,
    private val zone: TimeZone,
) {
    /** The current instant as a local wall-clock value — seeds the "Now" preset and the create screen. */
    fun nowLocal(): LocalDateTime = now().toLocalDateTime(zone)

    /**
     * Now, cut to the minute: a form states a start to the minute, so seconds the host cannot see would make the
     * created start differ from the shown one.
     */
    fun nowToTheMinute(): LocalDateTime = nowLocal().let { LocalDateTime(it.date, LocalTime(it.hour, it.minute)) }

    /** How long until the clock's next minute begins — never zero, so a clock that does not move waits a minute. */
    fun millisToNextMinute(): Long = MILLIS_PER_MINUTE - now().toEpochMilliseconds().mod(MILLIS_PER_MINUTE)

    /** A picked local wall-clock value as the capture date it names. */
    fun toCutoff(local: LocalDateTime): CaptureDate = localToCutoff(local, zone)

    /** A capture date as a local value for the picker. */
    fun toLocal(date: CaptureDate): LocalDateTime = date.at.toLocalDateTime(zone)

    /** A membership's or event's date as a local value. */
    fun toLocal(start: EventStart): LocalDateTime = toLocal(start.at)

    /** See the [EventStart] overload. */
    fun toLocal(end: EventEnd): LocalDateTime = toLocal(end.at)

    /** See the [EventStart] overload. */
    fun toLocal(floor: CaptureCutoff): LocalDateTime = toLocal(floor.at)

    /** See the [EventStart] overload. */
    fun toLocal(ceiling: CaptureCeiling): LocalDateTime = toLocal(ceiling.at)

    /**
     * "Now" as a capture date — the form the event-start comparison needs (`startsAt > nowCutoff()`): comparing
     * instants rather than local values needs no zone.
     */
    fun nowCutoff(): CaptureDate = toCutoff(nowLocal())

    /**
     * The latest end an event starting at [from] may have: [from] plus the
     * backend's event window ([EVENT_WINDOW_MAX_SECONDS], generated from the same deployment value
     * `POST /events` validates against).
     *
     * Measured in INSTANTS, not wall-clock days, because the backend measures `endsAt - startsAt` in seconds
     * between the two UTC values this formatter's [toCutoff] produces: across a daylight-saving change, "30
     * local days later" is an hour more or less than the limit, and the hour more is a refusal.
     */
    fun latestEnd(from: LocalDateTime): LocalDateTime =
        (from.toInstant(zone) + EVENT_WINDOW_MAX_SECONDS.seconds).toLocalDateTime(zone)

    /**
     * The open-until date of an event created now that guests may join for [days] more days (decision record
     * `changes/back-dated-events`, D1). Added in INSTANTS, like [latestEnd]: the backend bounds it in seconds from its
     * own now, and "30 local days" across a daylight-saving change is an hour more than the 30 days it admits.
     */
    fun openFor(days: Int): EventEnd = EventEnd(CaptureDate.of(now() + days.days))

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
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
