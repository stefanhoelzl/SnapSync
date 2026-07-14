package app.snapsync.presentation

import app.snapsync.config.localToCutoff
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Bridges the join screen's **local** date-time picker and the UTC `…Z` capture-date cutoff string
 * (capability `photo-selection-policy`). Injected into the screen so `:domain:ui` needs no clock or
 * timezone knowledge: it holds only a `LocalDateTime` and calls these three methods. The default
 * [SystemCutoffFormatter] is the production implementation (device clock + device zone); tests pass a
 * fixed clock/zone for determinism.
 */
interface CutoffFormatter {
    /** The current instant as a local wall-clock value — seeds the "Now" preset and the create screen. */
    fun nowLocal(): LocalDateTime

    /** Convert a picked local wall-clock value to the UTC `yyyy-MM-dd'T'HH:mm:ss'Z'` cutoff string. */
    fun toCutoff(local: LocalDateTime): String

    /** Parse a UTC `…Z` cutoff (the event's `startsAt`) back to a local value for the picker. */
    fun toLocal(cutoff: String): LocalDateTime?

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

/**
 * The production [CutoffFormatter]: the device's system [clock] and default [zone]. Reuses the config
 * cutoff codec ([localToCutoff]) so the string it produces is byte-identical to the enumerator's
 * `creationDate` shape (the lexicographic-compare invariant).
 */
class SystemCutoffFormatter(
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : CutoffFormatter {
    override fun nowLocal(): LocalDateTime = clock.now().toLocalDateTime(zone)

    override fun toCutoff(local: LocalDateTime): String = localToCutoff(local, zone)

    override fun toLocal(cutoff: String): LocalDateTime? =
        runCatching { Instant.parse(cutoff).toLocalDateTime(zone) }.getOrNull()
}
