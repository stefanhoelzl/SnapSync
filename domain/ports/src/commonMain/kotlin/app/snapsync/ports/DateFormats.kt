package app.snapsync.ports

import kotlinx.datetime.LocalDateTime

/**
 * How a date READS for this user (`docs/architecture.md`, "Localization"): month and weekday names, the order of
 * day and month, a 12- or 24-hour clock — all the platform's, from its CLDR data, never spelled out here. The
 * platform's own answer is the `DateFormatting` port's.
 *
 * [format] takes a CLDR **skeleton** — which fields to show, not where (`yMMMd`, `jm`); the platform turns it
 * into the locale's pattern. Write a skeleton's letters in the canonical order `y M E d j m` (the JVM's
 * `DateTimeFormatter.ofLocalizedPattern` refuses any other).
 */
interface DateFormats {
    /** [value] as the locale writes [skeleton]'s fields. */
    fun format(value: LocalDateTime, skeleton: String): String
}

/** Which locale a [DateFormats] formats in, as the parts it is built from. */
sealed interface DateLocale {
    /** The device's own locale, overrides included. */
    data object Device : DateLocale

    /** [language] in [region] (none: the language's default conventions). */
    data class Explicit(val language: String, val region: String?) : DateLocale
}
