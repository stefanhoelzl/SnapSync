package app.snapsync.ui.components

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The JVM's [DateFormats]: `java.time`'s CLDR patterns for a skeleton (JDK 19+). Its matcher does not widen a lone
 * field the way ICU does (`EEEE` resolves to `Mon`), so a skeleton of ONE field is used as the pattern it already is.
 */
actual fun DateFormats(languageTag: String?): DateFormats = PlatformDateFormats(languageTag)

private class PlatformDateFormats(languageTag: String?) : DateFormats {
    private val locale: Locale = Locale.getDefault(Locale.Category.FORMAT).let { device ->
        when (val resolved = resolveDateLocale(languageTag, device.language, device.country)) {
            DateLocale.Device -> device
            is DateLocale.Explicit -> Locale.Builder().setLanguage(resolved.language)
                .apply { resolved.region?.let(::setRegion) }.build()
        }
    }
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
