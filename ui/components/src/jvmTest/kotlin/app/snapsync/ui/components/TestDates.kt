package app.snapsync.ui.components

import app.snapsync.dates.JvmDateFormatting
import app.snapsync.model.resolveDateLocale

/** The JVM's real date formatting — the platform these tests run on — so a label reads as the locale writes it. */
internal val platformDates: (languageTag: String?) -> DateFormatter =
    { tag ->
        val dates = JvmDateFormatting()
        val device = dates.deviceLocale()
        DateFormatter(dates.formats(resolveDateLocale(tag, device.language, device.region))::format)
    }

/** The platform's [DateFormats] in [languageTag]. */
internal fun dateFormats(languageTag: String?): DateFormatter = platformDates(languageTag)
