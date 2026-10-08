package app.snapsync.ui.components

import app.snapsync.dates.JvmDateFormatting
import app.snapsync.model.DateFormats

/** The JVM's real date formatting — the platform these tests run on — so a label reads as the locale writes it. */
internal val platformDates: (languageTag: String?) -> DateFormats = JvmDateFormatting()::formats

/** The platform's [DateFormats] in [languageTag]. */
internal fun dateFormats(languageTag: String?): DateFormats = platformDates(languageTag)
