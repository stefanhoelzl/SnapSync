package app.snapsync.dates

import app.snapsync.ports.DateFormats
import app.snapsync.ports.DateFormatting
import app.snapsync.ports.DateLocale

/** **The locale a screen's dates read in** — the one rule every root applies to the language its strings resolved to. */
object DateLocales {
    /**
     * The locale a platform's [DateFormats] formats in, as the parts it is built from.
     *
     * A bare language (`en`, what the app passes: the language its strings are in) keeps the device's region, so an
     * English app on a German phone reads `5 Oct` and 24-hour time — the language of the words around it, the
     * conventions of the user. When the device already speaks that language, the device's own locale stands, every user
     * override it carries included. A tag with a region (`en-US`, what a test pins) is taken as it is; `null` or blank
     * is the device's own locale.
     */
    fun resolve(languageTag: String?, deviceLanguage: String, deviceRegion: String?): DateLocale {
        val tag = languageTag?.takeIf { it.isNotBlank() } ?: return DateLocale.Device
        val parts = tag.split('-', '_')
        return when {
            parts.size > 1 -> DateLocale.Explicit(language = parts[0], region = parts[1])
            parts[0].equals(deviceLanguage, ignoreCase = true) -> DateLocale.Device
            else -> DateLocale.Explicit(language = parts[0], region = deviceRegion?.takeIf { it.isNotBlank() })
        }
    }

    /**
     * The [DateFormats] a screen whose strings resolved to [languageTag] formats in, from [dates] — its language in the
     * device's region, the device's own locale where it already speaks it, `null` the device's own ([resolve]). What
     * every root hands its screen.
     */
    fun forScreen(dates: DateFormatting, languageTag: String?): DateFormats {
        val device = dates.deviceLocale()
        return dates.formats(resolve(languageTag, device.language, device.region))
    }
}
