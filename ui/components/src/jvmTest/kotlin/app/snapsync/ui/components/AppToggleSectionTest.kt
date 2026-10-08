package app.snapsync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The **switch-section** idiom of the join gate ([AppToggleSection]): the whole header row is ONE
 * `Role.Switch` node (the inner switch is drawing only), so assistive tech announces one on/off control per
 * section, and either the row or the switch flips it exactly once.
 */
@OptIn(ExperimentalTestApi::class)
class AppToggleSectionTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the header is a single switch node reflecting the checked state`() {
        var checked by mutableStateOf(true)
        rule.setContent {
            AppToggleSection(title = "Share my photos", checked = checked, onCheckedChange = { checked = it }) {
                AppSectionNote("a consequence line")
            }
        }
        // Exactly one switch — not two competing targets (the row + the drawn switch would double-fire).
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertCountEquals(1)
        rule.onNodeWithText("Share my photos")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        // The content slot renders beneath the header.
        rule.onNodeWithText("a consequence line").assertExists()
    }

    @Test
    fun `clicking the row toggles the section both ways`() {
        var checked by mutableStateOf(true)
        rule.setContent {
            AppToggleSection(
                title = "Receive everyone's photos",
                checked = checked,
                onCheckedChange = { checked = it },
            ) {}
        }
        rule.onNodeWithText("Receive everyone's photos").performClick()
        assertEquals(false, checked)
        rule.onNodeWithText("Receive everyone's photos")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))

        rule.onNodeWithText("Receive everyone's photos").performClick()
        assertEquals(true, checked)
        rule.onNodeWithText("Receive everyone's photos")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    }

    @Test
    fun `an off switch's track is the muted tone at 65 percent in light and 30 percent in dark`() {
        val light = offTrack(dark = false)
        assertClose(light.expected(alpha = 0.65f), light.drawn)
        val dark = offTrack(dark = true)
        assertClose(dark.expected(alpha = 0.30f), dark.drawn)
    }

    /** [drawn] is [expected] up to the renderer's blending: each channel within 2 of 255. */
    private fun assertClose(expected: Int, drawn: Int) {
        val off = listOf(
            16,
            8,
            0,
        ).maxOf { shift -> kotlin.math.abs((expected shr shift and 0xFF) - (drawn shr shift and 0xFF)) }
        assertTrue(off <= 2, "drew ${drawn.toUInt().toString(16)}, promised ${expected.toUInt().toString(16)}")
    }

    /** A pixel of an off switch's track, right of the thumb, and the two colours it is promised to blend. */
    private class Track(val drawn: Int, val muted: Color, val surface: Color) {
        fun expected(alpha: Float): Int = muted.copy(alpha = alpha).compositeOver(surface).toArgb()
    }

    private fun offTrack(dark: Boolean): Track {
        var muted = Color.Unspecified
        var surface = Color.Unspecified
        var drawn = 0
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalDarkThemeOverride provides dark, LocalReduceMotion provides true) {
                    AppTheme(platformDates) {
                        muted = MaterialTheme.colorScheme.onSurfaceVariant
                        surface = MaterialTheme.colorScheme.surface
                        Box(Modifier.background(surface).padding(4.dp)) { SectionSwitch(checked = false) }
                    }
                }
            }
            val pixels = onRoot().captureToImage().toPixelMap()
            // The thumb sits at the start; three quarters across, halfway down, is track.
            drawn = pixels[pixels.width * 3 / 4, pixels.height / 2].toArgb()
        }
        return Track(drawn, muted, surface)
    }
}
