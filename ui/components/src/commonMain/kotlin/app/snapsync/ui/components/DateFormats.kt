package app.snapsync.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import app.snapsync.model.DateFormats
import app.snapsync.ui.components.resources.Res
import app.snapsync.ui.components.resources.date_language
import org.jetbrains.compose.resources.stringResource

/**
 * The [DateFormats] every date in the design system reads through; [AppTheme] provides the app's. There is no default:
 * how a date reads is the platform's (the `DateFormatting` port), and a component rendered outside [AppTheme] without
 * one has no platform to ask.
 */
val LocalDateFormats = staticCompositionLocalOf<DateFormats> {
    error("no DateFormats provided — AppTheme provides the platform's, a test provides its own")
}

/**
 * The app's [DateFormats]: the platform's [formats] in the language the strings resolved to, so a date never reads in
 * another.
 */
@Composable
internal fun rememberDateFormats(formats: (languageTag: String?) -> DateFormats): DateFormats {
    val language = stringResource(Res.string.date_language)
    return remember(language, formats) { formats(language) }
}
