package app.snapsync.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import org.junit.Rule

/**
 * The 3-option capture-date cutoff ([AppCutoffChoices]): three `Role.RadioButton` rows (Now / Event start /
 * Custom), "Now" disabled before the event has started, and — the safety-critical seam — Custom that opens
 * the picker WITHOUT selecting itself, and coerces the confirmed instant up to the floor
 * (`max(chosen, eventStart)`, capability `photo-selection-policy`).
 *
 * A floor of `2026-03-10 09:30` is used — not the wall-clock "today" — so the calendar seed is deterministic.
 */
class AppCutoffChoicesTest {

    @get:Rule
    val rule = createComposeRule()

    private val floor = LocalDateTime(2026, 3, 10, 9, 30)

    private fun setChoices(
        selected: CutoffChoice = CutoffChoice.EVENT_START,
        nowAvailable: Boolean = true,
        customValue: LocalDateTime? = null,
        onSelect: (CutoffChoice) -> Unit = {},
        onCustomPicked: (LocalDateTime) -> Unit = {},
    ) {
        rule.setContent {
            // Snap the wheels instantly so the picker's OK returns the seeded time deterministically.
            CompositionLocalProvider(LocalReduceMotion provides true) {
                AppCutoffChoices(
                    selected = selected,
                    onSelect = onSelect,
                    nowAvailable = nowAvailable,
                    customValue = customValue,
                    onCustomPicked = onCustomPicked,
                    minimum = floor,
                    floorNote = "Can't be earlier than the event started.",
                )
            }
        }
    }

    @Test
    fun `the three rows are radio buttons with a single selection`() {
        setChoices(selected = CutoffChoice.EVENT_START)
        rule.onNodeWithText("Now")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsNotSelected()
        rule.onNodeWithText("Event start")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsSelected()
        rule.onNodeWithText("Custom")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsNotSelected()
    }

    @Test
    fun `Now is disabled before the event has started, Event start stays enabled`() {
        setChoices(nowAvailable = false)
        rule.onNodeWithText("Now").assertIsNotEnabled()
        rule.onNodeWithText("Event start").assertIsEnabled()
        // The disabled Now states why rather than being a choice that does nothing.
        rule.onNodeWithText("Same as the event start until the event begins.").assertExists()
    }

    @Test
    fun `Now enabled offers a live selection`() {
        var picked: CutoffChoice? = null
        setChoices(selected = CutoffChoice.EVENT_START, nowAvailable = true, onSelect = { picked = it })
        rule.onNodeWithText("Now").assertIsEnabled().performClick()
        assertEquals(CutoffChoice.NOW, picked)
    }

    @Test
    fun `tapping Custom opens the picker but does NOT select Custom`() {
        var selectedWith: CutoffChoice? = null
        setChoices(selected = CutoffChoice.EVENT_START, onSelect = { selectedWith = it })

        rule.onNodeWithText("Date & time").assertDoesNotExist()
        rule.onNodeWithText("Custom").performClick()

        // The tap opens the picker; only the dialog's OK commits the choice — onSelect is not called with
        // CUSTOM (nor anything) on the tap itself.
        rule.onNodeWithText("Date & time").assertExists()
        assertNull(selectedWith, "tapping Custom must not select it — the picker's OK does")
    }

    @Test
    fun `confirming the picker at the floor commits a value at or above the floor`() {
        var picked: LocalDateTime? = null
        setChoices(customValue = null, onCustomPicked = { picked = it })

        rule.onNodeWithText("Custom").performClick()
        // The picker seeds at the floor (customValue ?: minimum); OK returns it, coerced up to the floor.
        rule.onNodeWithText("OK").performClick()

        val committed = picked
        assertTrue(committed != null && committed >= floor, "the committed custom cutoff must be >= the floor")
        assertEquals(floor, committed)
    }

    @Test
    fun `cancelling the picker commits nothing and dismisses it`() {
        var picked: LocalDateTime? = null
        setChoices(onCustomPicked = { picked = it })

        rule.onNodeWithText("Custom").performClick()
        rule.onNodeWithText("Date & time").assertExists()
        // Standalone, the picker's Cancel is the only one on screen — unambiguous here.
        rule.onNodeWithText("Cancel").performClick()

        rule.onNodeWithText("Date & time").assertDoesNotExist()
        assertNull(picked, "a cancelled picker commits no cutoff")
    }
}
