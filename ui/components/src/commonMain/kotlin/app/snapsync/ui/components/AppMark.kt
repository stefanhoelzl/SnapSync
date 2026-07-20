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
        val s = this.size.minDimension / 100f

        fun cardPath(originX: Float, originY: Float, tiltDegrees: Float): Path {
            val path = Path()
            path.addRoundRect(
                RoundRect(
                    rect = Rect(
                        offset = Offset(originX * s, originY * s),
                        size = androidx.compose.ui.geometry.Size(48f * s, 48f * s),
                    ),
                    cornerRadius = CornerRadius(12f * s),
                ),
            )
            val m = Matrix()
            val cx = (originX + 24f) * s
            val cy = (originY + 24f) * s
            m.translate(cx, cy)
            m.rotateZ(-tiltDegrees) // PIL CCW → Compose CW
            m.translate(-cx, -cy)
            path.transform(m)
            return path
        }

        // The sun's centre, rotated with card A about A's centre (38, 38) — visually CCW by 11°,
        // which on a y-down canvas is a negative-angle rotation of the point.
        val angle = (-11.0 * kotlin.math.PI / 180.0).toFloat()
        val ax = 38f
        val ay = 38f
        val sx = 30f - ax
        val sy = 30f - ay
        val sunX = (ax + sx * cos(angle) - sy * sin(angle)) * s
        val sunY = (ay + sx * sin(angle) + sy * cos(angle)) * s

        val mark = Path().apply {
            fillType = PathFillType.EvenOdd
            addPath(cardPath(14f, 14f, 11f))
            addPath(cardPath(38f, 38f, -6f))
            addOval(Rect(center = Offset(sunX, sunY), radius = 6.5f * s))
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
                    colors = listOf(Color(0xFF34DDA2), Color(0xFF0B8A5E)),
                ),
                shape = RoundedCornerShape(size * 0.3f),
            ),
        contentAlignment = Alignment.Center,
    ) {
        AppMarkGlyph(size = size * 0.82f, color = Color.White)
    }
}
