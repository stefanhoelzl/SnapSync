package app.snapsync.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDateTime
import org.junit.Rule

/**
 * The dual-handle [DateTimeRangePickerDialog]: two labelled time-wheel pairs (From / Until), a tap-start
 * then tap-end span selection, a window that greys out-of-range days, and one OK that reports `[from, until]`.
 *
 * March 2026 is used (deterministic weekdays: `2026-03-09` is a Monday) so day content descriptions are
 * stable regardless of the wall-clock "today" the dialog reads for its "today" ring.
 */
class DateTimeRangePickerTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `both time-wheel pairs are labelled and expose the initial bounds`() {
        setPicker(from = LocalDateTime(2026, 3, 10, 9, 0), until = LocalDateTime(2026, 3, 12, 17, 0))
        rule.onNodeWithContentDescription("From hour", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "09"))
        rule.onNodeWithContentDescription("Until hour", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "17"))
    }

    @Test
    fun `tapping a new start then end reports the span and preserves the wheel times`() {
        var from: LocalDateTime? = null
        var until: LocalDateTime? = null
        setPicker(
            from = LocalDateTime(2026, 3, 10, 9, 0),
            until = LocalDateTime(2026, 3, 12, 17, 0),
            onConfirm = { f, u -> from = f; until = u },
        )
        // A complete range is showing, so the first tap RESETS to a new start; the second closes the span.
        rule.onNodeWithContentDescription("Wednesday 18 March 2026").performClick()
        rule.onNodeWithContentDescription("Friday 20 March 2026").performClick()
        rule.onNodeWithText("OK").performClick()

        // Only the dates changed; the From (09:00) and Until (17:00) wheel times are preserved.
        assertEquals(LocalDateTime(2026, 3, 18, 9, 0), from)
        assertEquals(LocalDateTime(2026, 3, 20, 17, 0), until)
    }

    @Test
    fun `days outside the window are disabled`() {
        setPicker(
            from = LocalDateTime(2026, 3, 12, 9, 0),
            until = LocalDateTime(2026, 3, 16, 17, 0),
            minimum = LocalDateTime(2026, 3, 10, 0, 0),
            maximum = LocalDateTime(2026, 3, 20, 0, 0),
        )
        rule.onNodeWithContentDescription("Monday 9 March 2026").assertIsNotEnabled()   // before the floor
        rule.onNodeWithContentDescription("Saturday 21 March 2026").assertIsNotEnabled() // after the ceiling
    }

    private fun setPicker(
        from: LocalDateTime,
        until: LocalDateTime,
        minimum: LocalDateTime? = null,
        maximum: LocalDateTime? = null,
        onConfirm: (LocalDateTime, LocalDateTime) -> Unit = { _, _ -> },
    ) {
        rule.setContent {
            // Snap the wheels instantly so nothing animates under the assertions.
            CompositionLocalProvider(LocalReduceMotion provides true) {
                DateTimeRangePickerDialog(
                    initialFrom = from,
                    initialUntil = until,
                    minimum = minimum,
                    maximum = maximum,
                    onDismiss = {},
                    onConfirm = onConfirm,
                )
            }
        }
    }
}
