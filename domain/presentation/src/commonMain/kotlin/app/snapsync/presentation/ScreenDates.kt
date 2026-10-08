package app.snapsync.presentation

import app.snapsync.model.DateFormats

/**
 * The process's date formatting, as the screen renders through it (`docs/architecture.md`, "Localization"): built by
 * the root over the `DateFormatting` port, as [CutoffFormatter] is built over the `Clock` port, and handed to the
 * platform's UI adapter — which may take no port and no function (`PortsNeverCallPortsTest`, `AdapterConstructorTest`).
 * The screen asks it in the language its strings resolved to.
 */
class ScreenDates(private val formatting: (languageTag: String?) -> DateFormats) {
    /** The platform's [DateFormats] in [languageTag] — see `resolveDateLocale`. */
    fun formats(languageTag: String?): DateFormats = formatting(languageTag)
}
