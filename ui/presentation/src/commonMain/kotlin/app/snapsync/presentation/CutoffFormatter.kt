package app.snapsync.presentation

import app.snapsync.model.localToCutoff
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
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
}
