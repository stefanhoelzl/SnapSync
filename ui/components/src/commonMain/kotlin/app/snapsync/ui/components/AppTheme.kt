package app.snapsync.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// SnapSync's green brand identity (the design-system skin — screens never see these). A confident
// emerald on cool near-white in light, a brighter green on near-black in dark. Destructive actions
// keep the Material error red by convention.
private val GreenLight = Color(0xFF0E9D6B)
private val GreenDark = Color(0xFF2FD69B)

// A **text-safe** brand green for SMALL uses (the tracked eyebrows, ~11sp bold). The brand [GreenLight]
// above is tuned as a *fill* (buttons, the day-cell disc, the on-switch) where it sits under white text;
// as small coloured text on the light background/surface it measures only ~3.2–3.5:1, below the 4.5:1
// WCAG floor for text. This darker shade measures 4.9:1 on `background` and 5.4:1 on `surface`, so an
// eyebrow reads at AA while the fills keep the brighter brand hue. Dark mode's [GreenDark] already clears
// 9:1 as text, so it is reused there unchanged — this token only splits the *light* value.
private val AccentTextLight = Color(0xFF0A7A53)

// The container/outline tokens M3 components reach for when a call site does not name a colour. They
// are NOT new hues: each is either an existing neutral of this palette or a tint of the SAME brand
// green above. Supplying them is a bug fix, not a restyle — an unspecified token falls back to the
// stock Material baseline (a violet-leaning tonal palette), which is how a *disabled* SegmentedButton
// rendered lavender: the call site pinned `activeContainerColor` but not its disabled twin, so the
// disabled selected segment resolved to the baseline `secondaryContainer`. Pinning the scheme means no
// M3 component anywhere can fall back that way, whether or not its call site remembered to.
private val LightColors = lightColorScheme(
    primary = GreenLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F0E6),
    onPrimaryContainer = Color(0xFF06452F),
    secondary = GreenLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9F0E6),
    onSecondaryContainer = Color(0xFF06452F),
    background = Color(0xFFF4F6F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8ECF1),
    onBackground = Color(0xFF14181D),
    onSurface = Color(0xFF14181D),
    onSurfaceVariant = Color(0xFF5B6472),
    // The text-field outline: darkened from the old #9BA4B2 (2.5:1 on surface, below the 3:1 floor for a
    // component boundary) to 3.6:1 on surface / 3.4:1 on background, so an unfocused field reads as a
    // bordered control rather than a ghost. Only OutlinedTextField (and any M3 default reaching for
    // `outline`) consumes this token; card/divider hairlines use the fainter `outlineVariant` below.
    outline = Color(0xFF7C8798),
    outlineVariant = Color(0xFFDCE1E8),
)

private val DarkColors = darkColorScheme(
    primary = GreenDark,
    onPrimary = Color(0xFF04231A),
    primaryContainer = Color(0xFF10382B),
    onPrimaryContainer = Color(0xFFAFEFD6),
    secondary = GreenDark,
    onSecondary = Color(0xFF04231A),
    secondaryContainer = Color(0xFF10382B),
    onSecondaryContainer = Color(0xFFAFEFD6),
    background = Color(0xFF0C0E12),
    surface = Color(0xFF171B22),
    surfaceVariant = Color(0xFF232833),
    onBackground = Color(0xFFEAEDF1),
    onSurface = Color(0xFFEAEDF1),
    onSurfaceVariant = Color(0xFF8B95A5),
    // Raised from #5B6472 (2.9:1 on surface, under the 3:1 boundary floor) to 3.7:1 on surface, so the
    // text-field outline clears the same threshold in dark as in light.
    outline = Color(0xFF6B7585),
    outlineVariant = Color(0xFF2B313C),
)

/**
 * A **test-only** ambient override for the theme selection. Default `null` means "follow the platform
 * light/dark setting" — production never provides it, so [AppTheme] behaves exactly as if it did not
 * exist. A desktop harness provides `true`/`false` to render the dark/light skin deterministically for
 * review (the desktop's own `isSystemInDarkTheme()` is unreliable). It is a CompositionLocal, not an
 * `App*` parameter, so it introduces no appearance parameter on any design-system signature.
 */
val LocalDarkThemeOverride = staticCompositionLocalOf<Boolean?> { null }

/**
 * Whether the platform's **reduce-motion** accessibility preference is on. Ambient rather than an `App*`
 * parameter for the same reason as [LocalDarkThemeOverride]: it is environment, not appearance, so no
 * design-system signature grows a styling knob.
 *
 * It defaults to `false` and the composition root supplies the truth (iOS reads
 * `UIAccessibility.isReduceMotionEnabled`), because Compose Multiplatform exposes no cross-platform
 * reduce-motion API — `LocalAccessibilityManager` is about announcements. A platform that cannot answer
 * simply does not provide it, and animation behaves as it always has.
 *
 * What honouring it costs is nothing, and that is the point: a `Pulsing` arrow is already **primary**-tinted
 * where a `Static` one is muted gray (see [AppStatusLine]), so the colour alone carries "in flight". The
 * motion is redundant emphasis. Dropping it removes the animation, not the meaning.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * The Material 3 skin. Follows the platform light/dark setting by default, unless
 * [LocalDarkThemeOverride] forces a theme (test harness only). The QR component always renders
 * dark-on-light on a light card (see [AppQrCode]) so it stays scannable in both themes — the skin
 * never inverts it.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (appIsDark()) DarkColors else LightColors,
        content = content,
    )
}

/**
 * Whether the app is currently rendering its dark skin — the same rule [AppTheme] selects the scheme by
 * ([LocalDarkThemeOverride] wins for the harness, else the platform setting). Skin-local semantic colours
 * that are NOT part of the M3 [MaterialTheme.colorScheme] (the amber attention line, the accent-text green)
 * read this so their light/dark split follows the scheme exactly.
 */
@Composable
fun appIsDark(): Boolean = LocalDarkThemeOverride.current ?: isSystemInDarkTheme()

/**
 * The **text-safe accent green** for small brand-tinted text (the tracked eyebrows). Not a colorScheme
 * token because `primary` must stay the brighter *fill* green; this is the same hue tuned to clear 4.5:1
 * as small text on the light surfaces, and reuses `primary` unchanged in dark (already ~9:1 as text).
 * See [AccentTextLight].
 */
@Composable
fun appAccentText(): Color = if (appIsDark()) GreenDark else AccentTextLight
