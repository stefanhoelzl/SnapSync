package app.snapsync.model

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
fun interface DateFormats {
    /** [value] as the locale writes [skeleton]'s fields. */
    fun format(value: LocalDateTime, skeleton: String): String
}

/**
 * The locale a platform's [DateFormats] formats in, as the parts it is built from — the one rule every adapter
 * applies to the language tag it is handed.
 *
 * A bare language (`en`, what the app passes: the language its strings are in) keeps the device's region, so an
 * English app on a German phone reads `5 Oct` and 24-hour time — the language of the words around it, the conventions
 * of the user. When the device already speaks that language, the device's own locale stands, every user override it
 * carries included. A tag with a region (`en-US`, what a test pins) is taken as it is; `null` or blank is the device's
 * own locale.
 */
fun resolveDateLocale(languageTag: String?, deviceLanguage: String, deviceRegion: String?): DateLocale {
    val tag = languageTag?.takeIf { it.isNotBlank() } ?: return DateLocale.Device
    val parts = tag.split('-', '_')
    return when {
        parts.size > 1 -> DateLocale.Explicit(language = parts[0], region = parts[1])
        parts[0].equals(deviceLanguage, ignoreCase = true) -> DateLocale.Device
        else -> DateLocale.Explicit(language = parts[0], region = deviceRegion?.takeIf { it.isNotBlank() })
    }
}

/** Which locale a [DateFormats] formats in — see [resolveDateLocale]. */
sealed interface DateLocale {
    /** The device's own locale, overrides included. */
    data object Device : DateLocale

    /** [language] in [region] (none: the language's default conventions). */
    data class Explicit(val language: String, val region: String?) : DateLocale
}
