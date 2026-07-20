package app.snapsync.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import kotlin.test.Test
import kotlinx.datetime.LocalDateTime
import org.junit.Rule

/**
 * Assistive-tech semantics of the hand-drawn [DateTimePickerDialog] — the picker is rendered directly (the
 * design system's components are otherwise reached through :ui:screens, but the calendar/wheel internals
 * warrant a direct probe). March 2026 is chosen so no cell coincides with the real wall-clock "today"
 * (which the dialog reads from `Clock.System`), keeping the day descriptions deterministic.
 *
 * `2026-03-09` is a Monday and `2026-03-15` is a Sunday (verified against the Gregorian calendar); the
 * floor at `2026-03-10` greys the earlier days.
 */
class DateTimePickerSemanticsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setPicker(
        initial: LocalDateTime = LocalDateTime(2026, 3, 15, 22, 8),
        minimum: LocalDateTime? = null,
    ) {
        rule.setContent {
            // Snap the wheels instantly so nothing animates under the assertions.
            CompositionLocalProvider(LocalReduceMotion provides true) {
                DateTimePickerDialog(
                    initial = initial,
                    minimum = minimum,
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
    }

    @Test
    fun `each day cell announces its full date, not a bare number`() {
        setPicker()
        // VoiceOver would otherwise hear "15"; it now hears the whole date.
        rule.onNodeWithContentDescription("Sunday 15 March 2026").assertExists()
        rule.onNodeWithContentDescription("Monday 9 March 2026").assertExists()
    }

    @Test
    fun `the chosen day reports selected`() {
        setPicker(initial = LocalDateTime(2026, 3, 15, 22, 8))
        rule.onNodeWithContentDescription("Sunday 15 March 2026").assertIsSelected()
    }

    @Test
    fun `days before the floor are present but disabled, days on or after are enabled`() {
        setPicker(minimum = LocalDateTime(2026, 3, 10, 0, 0))
        // Present in the tree (not silent) but inert — a guest hears why it can't be picked.
        rule.onNodeWithContentDescription("Monday 9 March 2026").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Tuesday 10 March 2026").assertIsEnabled()
    }

    @Test
    fun `the time wheels are labelled and expose their current value`() {
        setPicker(initial = LocalDateTime(2026, 3, 15, 22, 8))
        rule.onNodeWithContentDescription("Hour", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "22"))
        rule.onNodeWithContentDescription("Minute", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "08"))
    }

    @Test
    fun `the dialog title is a heading`() {
        setPicker()
        rule.onNodeWithText("Date & time")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }
}
