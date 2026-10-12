package app.snapsync.android.dates

import android.icu.text.DateFormat
import android.icu.util.TimeZone
import android.icu.util.ULocale
import app.snapsync.model.DateFormats
import app.snapsync.model.DateLocale
import app.snapsync.ports.DateFormatting
import app.snapsync.ports.DeviceLocale
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import java.util.Date
import java.util.Locale

/**
 * Android's [DateFormatting]: ICU's pattern for a skeleton. ICU formats an instant, so the wall-clock value is read as
 * UTC and formatted in UTC — the same fields come back out, with no zone in between.
 */
class AndroidDateFormatting : DateFormatting {
    override fun deviceLocale(): DeviceLocale = device().let { DeviceLocale(it.language, it.country.ifEmpty { null }) }

    override fun formats(locale: DateLocale): DateFormats = AndroidDateFormats(
        when (locale) {
            DateLocale.Device -> ULocale.forLocale(device())
            is DateLocale.Explicit -> ULocale(locale.language, locale.region.orEmpty())
        },
    )

    private fun device(): Locale = Locale.getDefault(Locale.Category.FORMAT)
}

private class AndroidDateFormats(private val locale: ULocale) : DateFormats {
    private val formatters = HashMap<String, DateFormat>()

    override fun format(value: LocalDateTime, skeleton: String): String =
        formatters.getOrPut(skeleton) {
            DateFormat.getInstanceForSkeleton(skeleton, locale).apply { timeZone = TimeZone.GMT_ZONE }
        }.format(Date(value.toInstant(kotlinx.datetime.TimeZone.UTC).toEpochMilliseconds()))
}
