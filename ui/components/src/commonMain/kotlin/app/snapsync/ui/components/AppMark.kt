package app.snapsync.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin


/**
 * The badge's emerald gradient stops — the app icon's own colorway, mirroring `scripts/appicon.py`'s
 * `TOP_LEFT`/`BOTTOM_RIGHT`. A shipped brand asset reproduced, deliberately NOT palette tokens: they
 * must track the icon script rather than `AppTheme`, and moving them there would invite someone to
 * "unify" them with the brand green and silently restyle the icon.
 */
private val BadgeGradientTop = Color(0xFF34DDA2)
private val BadgeGradientBottom = Color(0xFF0B8A5E)



/**
 * The mark is authored on a **100-unit square grid** — the same one `scripts/appicon.py` draws it on —
 * and every figure below is a coordinate on that grid, scaled to the requested size by `s`. The grid was
 * implicit until now, which is what made `cardPath(14f, 14f, 11f)` unreadable: those are grid units, not
 * pixels, dp, or fractions.
 *
 * Keeping the numbers as grid units rather than fractions is deliberate: they must stay comparable to
 * the Python script that produces the shipped app icon, and a divergence between the two is a visible
 * brand bug.
 */
private const val MARK_GRID = 100f

/** Each card is a 48-unit rounded square; the two are offset and counter-tilted about their centres. */
private const val CARD_SIZE = 48f
private const val CARD_CORNER_RADIUS = 12f
private const val CARD_HALF = CARD_SIZE / 2f

/** Card A: the lower-left card, tilted counter-clockwise. */
private const val CARD_A_ORIGIN = 14f
private const val CARD_A_TILT = 11f

/** Card B: the upper-right card, tilted the other way, whose overlap with A is knocked out. */
private const val CARD_B_ORIGIN = 38f
private const val CARD_B_TILT = -6f

/** The sun disc, whose centre rides with card A's rotation about A's centre. */
private const val SUN_CENTRE = 30f
private const val SUN_RADIUS = 6.5f

/** Degrees in a half turn — the radians conversion's denominator. */
private const val HALF_TURN_DEGREES = 180.0

/** The badge's squircle corner and the glyph's inset within it, as fractions of the badge size. */
private const val BADGE_CORNER_FRACTION = 0.3f
private const val BADGE_GLYPH_FRACTION = 0.82f


/**
 * The SnapSync mark, drawn — the same geometry as the app icon (`scripts/appicon.py`, the source of
 * truth for these constants): two photo-library cards, splayed (each turning on its own centre),
 * knocked through where they overlap — that shared region is the event — with one sun punched out of
 * the upper card so they read as photographs.
 *
 * The knock-outs come from **even-odd fill over three subpaths**: card A, card B, and the sun. A-only
 * regions have parity 1 (filled); the A∩B overlap has parity 2 (hole); the sun lies entirely inside
 * card A's solo region, so it too lands at parity 2 (hole) — exactly the script's "(A xor B) minus the
 * sun". The sun rotates *with* card A (its centre is rotated about A's centre), as in the script.
 *
 * PIL's `rotate` is counter-clockwise on a y-down canvas; Compose's rotation is clockwise on the same
 * orientation, hence the sign flips on the tilt constants.
 */
@Composable
fun AppMarkGlyph(size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension / MARK_GRID

        fun cardPath(originX: Float, originY: Float, tiltDegrees: Float): Path {
            val path = Path()
            path.addRoundRect(
                RoundRect(
                    rect = Rect(
                        offset = Offset(originX * s, originY * s),
                        size = androidx.compose.ui.geometry.Size(CARD_SIZE * s, CARD_SIZE * s),
                    ),
                    cornerRadius = CornerRadius(CARD_CORNER_RADIUS * s),
                ),
            )
            val m = Matrix()
            val cx = (originX + CARD_HALF) * s
            val cy = (originY + CARD_HALF) * s
            m.translate(cx, cy)
            m.rotateZ(-tiltDegrees) // PIL CCW → Compose CW
            m.translate(-cx, -cy)
            path.transform(m)
            return path
        }

        // The sun's centre, rotated with card A about A's centre (38, 38) — visually CCW by 11°,
        // which on a y-down canvas is a negative-angle rotation of the point.
        val angle = (-CARD_A_TILT.toDouble() * kotlin.math.PI / HALF_TURN_DEGREES).toFloat()
        val ax = CARD_B_ORIGIN
        val ay = CARD_B_ORIGIN
        val sx = SUN_CENTRE - ax
        val sy = SUN_CENTRE - ay
        val sunX = (ax + sx * cos(angle) - sy * sin(angle)) * s
        val sunY = (ay + sx * sin(angle) + sy * cos(angle)) * s

        val mark = Path().apply {
            fillType = PathFillType.EvenOdd
            addPath(cardPath(CARD_A_ORIGIN, CARD_A_ORIGIN, CARD_A_TILT))
            addPath(cardPath(CARD_B_ORIGIN, CARD_B_ORIGIN, CARD_B_TILT))
            addOval(Rect(center = Offset(sunX, sunY), radius = SUN_RADIUS * s))
        }
        drawPath(mark, color = color)
    }
}

/**
 * The mark as the **app-icon badge**: the white glyph on the icon's own emerald gradient, in a squircle.
 * The two gradient stops are the app icon's colorway (`scripts/appicon.py` `TOP_LEFT`/`BOTTOM_RIGHT`) —
 * a shipped brand asset reproduced, not a palette change; `AppTheme.kt` stays untouched.
 */
@Composable
fun AppMarkBadge(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(BadgeGradientTop, BadgeGradientBottom),
                ),
                shape = RoundedCornerShape(size * BADGE_CORNER_FRACTION),
            ),
        contentAlignment = Alignment.Center,
    ) {
        AppMarkGlyph(size = size * BADGE_GLYPH_FRACTION, color = Color.White)
    }
}
