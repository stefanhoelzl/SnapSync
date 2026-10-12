package app.snapsync.services.dates

import app.snapsync.model.DateFormats
import app.snapsync.model.resolveDateLocale
import app.snapsync.ports.DateFormatting

/**
 * The [DateFormats] a screen whose strings resolved to [languageTag] formats in — its language in the device's region,
 * the device's own locale where it already speaks it, `null` the device's own ([resolveDateLocale]). What every root
 * hands its screen.
 */
fun DateFormatting.formatsFor(languageTag: String?): DateFormats {
    val device = deviceLocale()
    return formats(resolveDateLocale(languageTag, device.language, device.region))
}
