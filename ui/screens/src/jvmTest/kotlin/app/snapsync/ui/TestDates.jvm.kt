package app.snapsync.ui

import app.snapsync.dates.DateLocales
import app.snapsync.dates.JvmDateFormatting
import app.snapsync.ui.components.DateFormatter

internal actual val testDates: (languageTag: String?) -> DateFormatter =
    { tag ->
        val dates = JvmDateFormatting()
        val device = dates.deviceLocale()
        DateFormatter(dates.formats(DateLocales.resolve(tag, device.language, device.region))::format)
    }
