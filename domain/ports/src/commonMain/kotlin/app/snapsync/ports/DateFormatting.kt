package app.snapsync.ports

import app.snapsync.model.DateFormats
import app.snapsync.model.DateLocale

/**
 * The platform's date formatting (`docs/architecture.md`, "Localization"): its CLDR data turning a skeleton into the
 * words, the order and the hour cycle a person reads — `java.time` on the JVM, ICU on Android, `NSDateFormatter` on
 * iOS. Only the app renders dates, so only the app's bundle carries it; the upload extension has none.
 *
 * It formats in the locale it is given and says what the device's own is; which locale a screen formats in is the
 * core's (`resolveDateLocale`).
 */
interface DateFormatting : Port {
    /** The device's own language and region, as the platform states them. */
    fun deviceLocale(): DeviceLocale

    /** The [DateFormats] of [locale]: the device's own, overrides included, or a language in a region. */
    fun formats(locale: DateLocale): DateFormats
}

/** The device's own locale: its [language], and its [region] where it has one. */
data class DeviceLocale(val language: String, val region: String?)
