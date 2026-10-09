package app.snapsync.ui.components

import app.snapsync.dates.JvmDateFormatting

/** The JVM's real date formatting — the platform these tests run on — so a label reads as the locale writes it. */
internal val platformDates: (languageTag: String?) -> DateFormatter =
    { tag -> DateFormatter(JvmDateFormatting().formats(tag)::format) }

/** The platform's [DateFormats] in [languageTag]. */
internal fun dateFormats(languageTag: String?): DateFormatter = platformDates(languageTag)
