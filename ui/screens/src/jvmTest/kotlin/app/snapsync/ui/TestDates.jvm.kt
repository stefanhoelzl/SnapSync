package app.snapsync.ui

import app.snapsync.dates.JvmDateFormatting
import app.snapsync.ui.components.DateFormatter

internal actual val testDates: (languageTag: String?) -> DateFormatter =
    { tag -> DateFormatter(JvmDateFormatting().formats(tag)::format) }
