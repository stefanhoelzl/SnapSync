package app.snapsync.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import app.snapsync.ui.components.resources.Res
import app.snapsync.ui.components.resources.date_language
import kotlinx.datetime.LocalDateTime
import org.jetbrains.compose.resources.stringResource

/**
 * How a date reads in one language: [format] writes a value with the fields an ICU skeleton names, ordered and spelled
 * as the language does. The platform's — the `DateFormatting` port answers it — handed in by the app's root.
 */
fun interface DateFormatter {
    fun format(value: LocalDateTime, skeleton: String): String
}

/**
 * The screen's date formatting, as a value a screen's host is handed: [forLanguage] answers the [DateFormatter] for the
 * language the strings resolved to. A value rather than a bare function so a platform UI adapter can hold it — an
 * adapter takes no function and no port (`AdapterConstructorTest`, `PortsNeverCallPortsTest`); the app's root builds it
 * over the `DateFormatting` port.
 */
class ScreenDates(val forLanguage: (languageTag: String?) -> DateFormatter)

/**
 * The [DateFormatter] every date in the design system reads through; [AppTheme] provides the app's. There is no
 * default: how a date reads is the platform's (the `DateFormatting` port), and a component rendered outside [AppTheme]
 * without one has no platform to ask.
 */
val LocalDateFormats = staticCompositionLocalOf<DateFormatter> {
    error("no DateFormatter provided — AppTheme provides the platform's, a test provides its own")
}

/**
 * The app's [DateFormatter]: the platform's [formats] in the language the strings resolved to, so a date never reads in
 * another.
 */
@Composable
internal fun rememberDateFormats(formats: (languageTag: String?) -> DateFormatter): DateFormatter {
    val language = stringResource(Res.string.date_language)
    return remember(language, formats) { formats(language) }
}
