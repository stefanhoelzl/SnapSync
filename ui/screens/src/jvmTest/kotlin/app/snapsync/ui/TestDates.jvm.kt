package app.snapsync.ui

import app.snapsync.dates.JvmDateFormatting
import app.snapsync.model.resolveDateLocale
import app.snapsync.ui.components.DateFormatter

internal actual val testDates: (languageTag: String?) -> DateFormatter =
    { tag ->
        val dates = JvmDateFormatting()
        val device = dates.deviceLocale()
        DateFormatter(dates.formats(resolveDateLocale(tag, device.language, device.region))::format)
    }
