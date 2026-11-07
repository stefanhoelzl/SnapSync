@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import app.snapsync.control.Verifies
import kotlin.test.Test
import kotlin.test.assertTrue

private const val INVITE = "https://snapsync.app/e/00000000-0000-0000-0000-000000000000"
private const val CAPTION = "Let family and friends scan this with their camera"

// [AppQrCode]'s layout: a 196 dp code, 12 dp above its caption and centred on it, inside a 20 dp card padding. The
// test reads the code with a margin of that padding round it.
private val CODE = 196.dp
private val GAP = 12.dp
private val MARGIN = 8.dp

private const val DARK = 0.2f
private const val LIGHT = 0.9f

/**
 * The QR code's own colours (capability `invite-link`): the code is drawn dark on light in either appearance. Whether
 * a camera then reads it is the scanner's; what is ours is that the skin never inverts the code.
 */
class AppQrCodeTest {

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "The joined screen offers the invite until the event's photos are deleted",
        scenario = "The QR stays scannable in dark mode",
    )
    fun `the code is drawn dark on light in the dark appearance as in the light`() {
        for (dark in listOf(true, false)) {
            val appearance = if (dark) "dark" else "light"
            val code = drawnCode(dark)
            val box = checkNotNull(code.darkBounds()) { "in the $appearance appearance nothing of the code is dark" }
            // Light all round: the dark modules sit on the light card, not on a dark ground.
            assertTrue(
                box.left > 0 && box.top > 0 && box.right < code.width - 1 && box.bottom < code.height - 1,
                "in the $appearance appearance the dark reaches the edge of the card ($box in ${code.width} px)",
            )
            val ring = (box.left - 1..box.right + 1).flatMap { listOf(it to box.top - 1, it to box.bottom + 1) } +
                (box.top..box.bottom).flatMap { listOf(box.left - 1 to it, box.right + 1 to it) }
            assertTrue(
                ring.all { (x, y) -> code[x, y] > LIGHT },
                "in the $appearance appearance the code has no light margin",
            )
            // A finder pattern's corner: the modules are the dark, the ground is the light.
            assertTrue(
                code[box.left + 1, box.top + 1] < DARK,
                "in the $appearance appearance the code's corner is not dark",
            )
            val inside = (box.top..box.bottom).flatMap { y -> (box.left..box.right).map { x -> code[x, y] } }
            assertTrue(inside.count { it < DARK } > inside.size / 5, "in the $appearance appearance too little is dark")
            assertTrue(
                inside.count { it > LIGHT } > inside.size / 5,
                "in the $appearance appearance too little is light",
            )
        }
    }

    /** The luminance of the code's region and a margin round it, as the sheet draws it in the [dark] or light skin. */
    private fun drawnCode(dark: Boolean): Luminance {
        var code: Luminance? = null
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalDarkThemeOverride provides dark) {
                    AppTheme(platformDates) {
                        AppQrSheet(title = "Join Anna's Birthday", content = INVITE, caption = CAPTION, onDismiss = {})
                    }
                }
            }
            waitForIdle()
            code = read()
        }
        return checkNotNull(code)
    }

    private fun ComposeUiTest.read(): Luminance {
        val bounds = onNodeWithText(CAPTION).getBoundsInRoot()
        val pixels = onNode(isRoot() and hasAnyDescendant(hasText(CAPTION))).captureToImage().toPixelMap()
        return with(density) {
            val size = CODE.roundToPx()
            val margin = MARGIN.roundToPx()
            val left = ((bounds.left + bounds.right) / 2).roundToPx() - size / 2 - margin
            val top = (bounds.top - GAP).roundToPx() - size - margin
            val side = size + 2 * margin
            Luminance(side, side) { x, y -> pixels[left + x, top + y].luminance() }
        }
    }
}

/** A [width] × [height] grid of luminance. */
private class Luminance(val width: Int, val height: Int, read: (Int, Int) -> Float) {
    private val values = FloatArray(width * height) { read(it % width, it / width) }

    operator fun get(x: Int, y: Int): Float = values[y * width + x]

    /** The smallest box holding every dark pixel, or null when none is. */
    fun darkBounds(): IntRect? {
        val dark = values.indices.filter { values[it] < DARK }
        if (dark.isEmpty()) return null
        return IntRect(
            dark.minOf { it % width },
            dark.minOf { it / width },
            dark.maxOf { it % width },
            dark.maxOf { it / width },
        )
    }
}
