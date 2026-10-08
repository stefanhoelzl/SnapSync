package app.snapsync.ui

import app.snapsync.dates.JvmDateFormatting
import app.snapsync.model.DateFormats

internal actual val testDates: (languageTag: String?) -> DateFormats = JvmDateFormatting()::formats
