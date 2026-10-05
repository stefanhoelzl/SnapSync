package app.snapsync.ui.components

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.languageCode
import platform.Foundation.timeZoneForSecondsFromGMT

/**
 * iOS's [DateFormats]: Foundation's pattern for a skeleton (`dateFormatFromTemplate`). Foundation formats an
 * instant, so the wall-clock value is read as UTC and formatted in UTC — the same fields come back out.
 */
actual fun DateFormats(languageTag: String?): DateFormats = PlatformDateFormats(languageTag)

private class PlatformDateFormats(languageTag: String?) : DateFormats {
    private val locale: NSLocale = NSLocale.currentLocale.let { device ->
        when (val resolved = resolveDateLocale(languageTag, device.languageCode, device.countryCode)) {
            DateLocale.Device -> device
            is DateLocale.Explicit ->
                NSLocale(localeIdentifier = listOfNotNull(resolved.language, resolved.region).joinToString("_"))
        }
    }
    private val formatters = HashMap<String, NSDateFormatter>()

    override fun format(value: LocalDateTime, skeleton: String): String {
        val formatter = formatters.getOrPut(skeleton) {
            NSDateFormatter().also {
                it.locale = locale
                it.timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
                it.dateFormat = NSDateFormatter.dateFormatFromTemplate(skeleton, 0u, locale) ?: skeleton
            }
        }
        val seconds = value.toInstant(TimeZone.UTC).toEpochMilliseconds() / MILLIS_PER_SECOND
        return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(seconds))
    }
}

private const val MILLIS_PER_SECOND = 1000.0
