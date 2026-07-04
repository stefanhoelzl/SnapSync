package app.snapsync.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
 * The Material 3 skin. Follows the platform light/dark setting; the QR component always renders
 * dark-on-light on a light card (see [AppQrCode]) so it stays scannable in both themes — the skin
 * never inverts it.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
