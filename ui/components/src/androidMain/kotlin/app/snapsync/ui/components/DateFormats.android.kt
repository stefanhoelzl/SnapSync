package app.snapsync.ui.components

import android.icu.text.DateFormat
import android.icu.util.TimeZone
import android.icu.util.ULocale
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import java.util.Date
import java.util.Locale

/**
 * Android's [DateFormats]: ICU's pattern for a skeleton. ICU formats an instant, so the wall-clock value is read
 * as UTC and formatted in UTC — the same fields come back out, with no zone in between.
 */
actual fun DateFormats(languageTag: String?): DateFormats = PlatformDateFormats(languageTag)

private class PlatformDateFormats(languageTag: String?) : DateFormats {
    private val locale: ULocale = Locale.getDefault(Locale.Category.FORMAT).let { device ->
        when (val resolved = resolveDateLocale(languageTag, device.language, device.country)) {
            DateLocale.Device -> ULocale.forLocale(device)
            is DateLocale.Explicit -> ULocale(resolved.language, resolved.region.orEmpty())
        }
    }
    private val formatters = HashMap<String, DateFormat>()

    override fun format(value: LocalDateTime, skeleton: String): String =
        formatters.getOrPut(skeleton) {
            DateFormat.getInstanceForSkeleton(skeleton, locale).apply { timeZone = TimeZone.GMT_ZONE }
        }.format(Date(value.toInstant(kotlinx.datetime.TimeZone.UTC).toEpochMilliseconds()))
}
