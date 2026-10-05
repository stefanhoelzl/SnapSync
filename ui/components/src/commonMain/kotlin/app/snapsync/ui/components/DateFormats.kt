package app.snapsync.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import app.snapsync.ui.components.resources.Res
import app.snapsync.ui.components.resources.date_language
import kotlinx.datetime.LocalDateTime
import org.jetbrains.compose.resources.stringResource

/**
 * How a date READS for this user (`docs/architecture.md`, "Localization"): month and weekday names, the order of
 * day and month, a 12- or 24-hour clock — all the platform's, from its CLDR data, never spelled out here.
 *
 * [format] takes a CLDR **skeleton** — which fields to show, not where (`yMMMd`, `jm`); the platform turns it
 * into the locale's pattern. Write a skeleton's letters in the canonical order `y M E d j m` (the JVM's
 * `DateTimeFormatter.ofLocalizedPattern` refuses any other).
 *
 * [languageTag] decides the language. A bare language (`en`, what [rememberDateFormats] passes: the language
 * the app's strings are in) keeps the device's region, so an English app on a German phone reads `5 Oct` and
 * 24-hour time — the language of the words around it, the conventions of the user. A tag with a region
 * (`en-US`, what a test pins) is taken as it is; `null` is the device's own locale.
 */
fun interface DateFormats {
    /** [value] as the locale writes [skeleton]'s fields. */
    fun format(value: LocalDateTime, skeleton: String): String
}

/** The platform's [DateFormats] in [languageTag] — see [DateFormats]. */
expect fun DateFormats(languageTag: String?): DateFormats

/** The [DateFormats] every date in the design system reads through; [AppTheme] provides the app's. */
val LocalDateFormats = staticCompositionLocalOf { DateFormats(null) }

/** The app's [DateFormats]: in the language its strings resolved to, so a date never reads in another. */
@Composable
internal fun rememberDateFormats(): DateFormats {
    val language = stringResource(Res.string.date_language)
    return remember(language) { DateFormats(language) }
}

/**
 * The locale [DateFormats] formats in, as the parts it is built from: [device] when it already speaks
 * [languageTag]'s language (keeping every user override it carries), else that language in the device's region.
 */
internal fun resolveDateLocale(languageTag: String?, deviceLanguage: String, deviceRegion: String?): DateLocale {
    val tag = languageTag?.takeIf { it.isNotBlank() } ?: return DateLocale.Device
    val parts = tag.split('-', '_')
    return when {
        parts.size > 1 -> DateLocale.Explicit(language = parts[0], region = parts[1])
        parts[0].equals(deviceLanguage, ignoreCase = true) -> DateLocale.Device
        else -> DateLocale.Explicit(language = parts[0], region = deviceRegion?.takeIf { it.isNotBlank() })
    }
}

/** Which locale a [DateFormats] formats in — see [resolveDateLocale]. */
internal sealed interface DateLocale {
    /** The device's own locale, overrides included. */
    data object Device : DateLocale

    /** [language] in [region] (none: the language's default conventions). */
    data class Explicit(val language: String, val region: String?) : DateLocale
}
