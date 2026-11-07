@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.ui

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
import androidx.compose.ui.unit.dp
import app.snapsync.ui.components.AppQrCode

// `AppQrCode`'s layout: a 196 dp code, 12 dp above its caption and centred on it. A layout change shows here as codes
// that no longer agree, never as a pass.
private val CODE = 196.dp
private val GAP = 12.dp

/** A margin inside the code's edge, so a pixel of rounding where the region starts compares nothing outside it. */
private const val INSET = 2

/**
 * Which pixels of the QR code drawn above [caption] are dark, row by row: the code's modules as they were drawn,
 * comparable with another drawing of a code ([agreement]). It reads pixels because the code carries no semantics.
 */
internal fun ComposeUiTest.qrModules(caption: String): List<Boolean> {
    val text = onNodeWithText(caption)
    val bounds = text.getBoundsInRoot()
    val pixels = onNode(isRoot() and hasAnyDescendant(hasText(caption))).captureToImage().toPixelMap()
    return with(density) {
        val size = CODE.roundToPx()
        val left = ((bounds.left + bounds.right) / 2).roundToPx() - size / 2
        val top = (bounds.top - GAP).roundToPx() - size
        buildList {
            for (y in top + INSET until top + size - INSET) {
                for (x in left + INSET until left + size - INSET) add(pixels[x, y].luminance() < 0.5f)
            }
        }
    }
}

/** The modules of the code [AppQrCode] draws for [content], drawn alone, as [qrModules] reads them. */
internal fun qrModulesOf(content: String, caption: String): List<Boolean> {
    var modules: List<Boolean> = emptyList()
    runComposeUiTest {
        setContent { AppQrCode(content = content, caption = caption) }
        waitForIdle()
        modules = qrModules(caption)
    }
    return modules
}

/** The share of pixels on which two drawings of a code agree: 1 for the same code, about a half for another. */
internal fun agreement(a: List<Boolean>, b: List<Boolean>): Double {
    check(a.size == b.size && a.isNotEmpty()) { "the codes were read at different sizes: ${a.size} and ${b.size}" }
    return a.indices.count { a[it] == b[it] }.toDouble() / a.size
}
