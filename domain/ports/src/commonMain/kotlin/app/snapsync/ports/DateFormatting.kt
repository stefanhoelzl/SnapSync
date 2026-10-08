package app.snapsync.ports

import app.snapsync.model.DateFormats

/**
 * The platform's date formatting (`docs/architecture.md`, "Localization"): its CLDR data turning a skeleton into the
 * words, the order and the hour cycle a person reads — `java.time` on the JVM, ICU on Android, `NSDateFormatter` on
 * iOS. Only the app renders dates, so only the app's bundle carries it; the upload extension has none.
 *
 * [formats] answers the [DateFormats] for [languageTag], resolved by `resolveDateLocale` — a bare language in the
 * device's region, a tag with a region as it is, `null` the device's own locale. The screen asks with the language its
 * strings resolved to, so a date never reads in another.
 */
interface DateFormatting : Port {
    fun formats(languageTag: String?): DateFormats
}
