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

// The rest of the palette, one NAME PER DISTINCT VALUE. Naming these is not decoration: seven of them
// were written twice, once in each scheme, where changing a value and forgetting its twin is silent —
// `GreenContainerLight` was `primaryContainer` and `secondaryContainer` spelled out separately, and so
// were their `on*` partners in both schemes. A duplicated literal cannot drift once it has one home.
//
// Named for the ROLE in the palette rather than the hue, because that is what a reader is looking for
// when they arrive here: the schemes below read as a mapping from M3 slot to palette role, and a slot
// whose value looks wrong is checked by reading one line.

// Light — the brand green's container pair, shared by `primary*` and `secondary*`.
private val GreenContainerLight = Color(0xFFD9F0E6)
private val OnGreenContainerLight = Color(0xFF06452F)

// Light — surfaces and the text that sits on them.
private val BackgroundLight = Color(0xFFF4F6F8)
private val SurfaceVariantLight = Color(0xFFE8ECF1)
private val OnSurfaceLight = Color(0xFF14181D)
private val OnSurfaceVariantLight = Color(0xFF5B6472)

// Light — boundaries. See the note at `outline` below for why these two differ in weight.
private val OutlineLight = Color(0xFF7C8798)
private val OutlineVariantLight = Color(0xFFDCE1E8)

// Dark — the brand green's container pair and the ink that sits ON the green, shared as above.
private val OnGreenDark = Color(0xFF04231A)
private val GreenContainerDark = Color(0xFF10382B)
private val OnGreenContainerDark = Color(0xFFAFEFD6)

// Dark — surfaces and the text that sits on them.
private val BackgroundDark = Color(0xFF0C0E12)
private val SurfaceDark = Color(0xFF171B22)
private val SurfaceVariantDark = Color(0xFF232833)
private val OnSurfaceDark = Color(0xFFEAEDF1)
private val OnSurfaceVariantDark = Color(0xFF8B95A5)

// Dark — boundaries.
private val OutlineDark = Color(0xFF6B7585)
private val OutlineVariantDark = Color(0xFF2B313C)

// The attention line's amber (capability `sync-status-screen`), read through [appAttentionText] /
// [appAttentionContainer] below. It lives HERE and not beside the component that draws it because a
// design system with two places to define a colour has two places for one to drift; the component keeps
// the behaviour, the palette keeps the values.
//
// Amber is not an M3 `colorScheme` token, so its light/dark split is picked by hand via [appIsDark] —
// the same rule the scheme itself is chosen by.
//
// Light was failing WCAG: #E8820C text on a 0x22 amber pill measured ~2.24:1 (needs 4.5:1 at 16sp), and
// the pill barely separated from the page (1.13:1). Light now uses a dark amber for text/icons (5.0:1 on
// the pill) over a stronger 0x3D fill (pill/page ~1.25:1). Dark already passed (5.2-6.0:1) and is kept.
private val AmberTextLight = Color(0xFF8A4B00)
private val AmberTextDark = Color(0xFFE8820C)
private val AmberContainerLight = Color(0x3DE8820C)
private val AmberContainerDark = Color(0x22E8820C)

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
    primaryContainer = GreenContainerLight,
    onPrimaryContainer = OnGreenContainerLight,
    secondary = GreenLight,
    onSecondary = Color.White,
    secondaryContainer = GreenContainerLight,
    onSecondaryContainer = OnGreenContainerLight,
    background = BackgroundLight,
    surface = Color.White,
    surfaceVariant = SurfaceVariantLight,
    onBackground = OnSurfaceLight,
    onSurface = OnSurfaceLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    // The text-field outline: darkened from the old #9BA4B2 (2.5:1 on surface, below the 3:1 floor for a
    // component boundary) to 3.6:1 on surface / 3.4:1 on background, so an unfocused field reads as a
    // bordered control rather than a ghost. Only OutlinedTextField (and any M3 default reaching for
    // `outline`) consumes this token; card/divider hairlines use the fainter `outlineVariant` below.
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
)

private val DarkColors = darkColorScheme(
    primary = GreenDark,
    onPrimary = OnGreenDark,
    primaryContainer = GreenContainerDark,
    onPrimaryContainer = OnGreenContainerDark,
    secondary = GreenDark,
    onSecondary = OnGreenDark,
    secondaryContainer = GreenContainerDark,
    onSecondaryContainer = OnGreenContainerDark,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onBackground = OnSurfaceDark,
    onSurface = OnSurfaceDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    // Raised from #5B6472 (2.9:1 on surface, under the 3:1 boundary floor) to 3.7:1 on surface, so the
    // text-field outline clears the same threshold in dark as in light.
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
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

/**
 * The attention line's amber text/icon colour (capability `sync-status-screen`). A skin-local semantic
 * colour, not an M3 `colorScheme` token, so it follows [appIsDark] the way the scheme itself does.
 */
@Composable
internal fun appAttentionText(): Color = if (appIsDark()) AmberTextDark else AmberTextLight

/** The attention line's amber pill fill. See [appAttentionText]. */
@Composable
internal fun appAttentionContainer(): Color = if (appIsDark()) AmberContainerDark else AmberContainerLight
