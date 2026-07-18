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

private val LightColors = lightColorScheme(
    primary = GreenLight,
    onPrimary = Color.White,
    secondary = GreenLight,
    background = Color(0xFFF4F6F8),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF14181D),
    onSurface = Color(0xFF14181D),
    onSurfaceVariant = Color(0xFF5B6472),
)

private val DarkColors = darkColorScheme(
    primary = GreenDark,
    onPrimary = Color(0xFF04231A),
    secondary = GreenDark,
    background = Color(0xFF0C0E12),
    surface = Color(0xFF171B22),
    onBackground = Color(0xFFEAEDF1),
    onSurface = Color(0xFFEAEDF1),
    onSurfaceVariant = Color(0xFF8B95A5),
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
    val dark = LocalDarkThemeOverride.current ?: isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
