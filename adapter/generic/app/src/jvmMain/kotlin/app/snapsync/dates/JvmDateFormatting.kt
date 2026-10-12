package app.snapsync.dates

import app.snapsync.model.DateFormats
import app.snapsync.model.DateLocale
import app.snapsync.ports.DateFormatting
import app.snapsync.ports.DeviceLocale
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The JVM's [DateFormatting]: `java.time`'s CLDR patterns for a skeleton (JDK 19+). Its matcher does not widen a lone
 * field the way ICU does (`EEEE` resolves to `Mon`), so a skeleton of ONE field is used as the pattern it already is.
 */
class JvmDateFormatting : DateFormatting {
    override fun deviceLocale(): DeviceLocale = device().let { DeviceLocale(it.language, it.country.ifEmpty { null }) }

    override fun formats(locale: DateLocale): DateFormats = JvmDateFormats(
        when (locale) {
            DateLocale.Device -> device()
            // An empty region is the language's default conventions, as no region at all.
            is DateLocale.Explicit -> Locale.Builder().setLanguage(
                locale.language,
            ).setRegion(locale.region.orEmpty()).build()
        },
    )

    private fun device(): Locale = Locale.getDefault(Locale.Category.FORMAT)
}

private class JvmDateFormats(private val locale: Locale) : DateFormats {
    private val formatters = HashMap<String, DateTimeFormatter>()

    override fun format(value: LocalDateTime, skeleton: String): String =
        formatters.getOrPut(skeleton) {
            if (skeleton.toSet().size == 1) {
                DateTimeFormatter.ofPattern(skeleton, locale)
            } else {
                DateTimeFormatter.ofLocalizedPattern(skeleton).withLocale(locale)
            }
        }
            .format(value.toJavaLocalDateTime())
}
