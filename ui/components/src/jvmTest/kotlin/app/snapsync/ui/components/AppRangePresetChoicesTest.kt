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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import org.junit.Rule

/**
 * The From/Until range preset selector ([AppRangePresetChoices]): a **From** group
 * (Event start / Now / Custom) and an **Until** group (Event end / Custom), each row a `Role.RadioButton`,
 * "Now" disabled outside the event window, and — the safety-critical seam — per-handle Custom rows that open
 * the window-constrained picker WITHOUT selecting themselves, coercing the confirmed instant into the window.
 *
 * A fixed window `[2026-03-10 09:30, 2026-03-20 18:00]` is used — not the wall-clock "today" — so the
 * calendar seed is deterministic. The two "Custom" rows share a label, so they are addressed by testTag.
 */
class AppRangePresetChoicesTest {

    @get:Rule
    val rule = createComposeRule()

    private val windowStart = LocalDateTime(2026, 3, 10, 9, 30)
    private val windowEnd = LocalDateTime(2026, 3, 20, 18, 0)

    private fun setChoices(
        fromSelected: FromChoice = FromChoice.EVENT_START,
        untilSelected: UntilChoice = UntilChoice.EVENT_END,
        nowAvailable: Boolean = true,
        fromCustomValue: LocalDateTime? = null,
        untilCustomValue: LocalDateTime? = null,
        onFromSelect: (FromChoice) -> Unit = {},
        onUntilSelect: (UntilChoice) -> Unit = {},
        onFromCustomPicked: (LocalDateTime) -> Unit = {},
        onUntilCustomPicked: (LocalDateTime) -> Unit = {},
    ) {
        rule.setContent {
            // Snap the wheels instantly so the picker's OK returns the seeded time deterministically.
            CompositionLocalProvider(LocalReduceMotion provides true) {
                AppRangePresetChoices(
                    fromSelected = fromSelected,
                    onFromSelect = onFromSelect,
                    fromCustomValue = fromCustomValue,
                    onFromCustomPicked = onFromCustomPicked,
                    untilSelected = untilSelected,
                    onUntilSelect = onUntilSelect,
                    untilCustomValue = untilCustomValue,
                    onUntilCustomPicked = onUntilCustomPicked,
                    nowAvailable = nowAvailable,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    fromFloorNote = "Can't be earlier than the event started.",
                    untilCeilingNote = "Can't be later than the event ends.",
                )
            }
        }
    }

    @Test
    fun `both handles show radio rows with a single selection each`() {
        setChoices(fromSelected = FromChoice.EVENT_START, untilSelected = UntilChoice.EVENT_END)
        rule.onNodeWithTag("from-event-start").assertIsRadio().assertIsSelected()
        rule.onNodeWithTag("from-now").assertIsRadio().assertIsNotSelected()
        rule.onNodeWithTag("from-custom").assertIsRadio().assertIsNotSelected()
        rule.onNodeWithTag("until-event-end").assertIsRadio().assertIsSelected()
        rule.onNodeWithTag("until-custom").assertIsRadio().assertIsNotSelected()
    }

    /**
     * The two handles are two SEPARATE grouped sub-lists, each with its caption above its own well. Asserted
     * by CONTAINMENT only — a row is inside its own group and not inside the other's — never by counting
     * intermediate nodes, so the well's internals stay free to change.
     */
    @Test
    fun `each handle's rows and caption live in their own group`() {
        setChoices()

        for (row in listOf("from-event-start", "from-now", "from-custom")) {
            rule.onNode(hasTestTag(row) and hasAnyAncestor(hasTestTag("from-group"))).assertExists()
            rule.onNode(hasTestTag(row) and hasAnyAncestor(hasTestTag("until-group"))).assertDoesNotExist()
        }
        for (row in listOf("until-event-end", "until-custom")) {
            rule.onNode(hasTestTag(row) and hasAnyAncestor(hasTestTag("until-group"))).assertExists()
            rule.onNode(hasTestTag(row) and hasAnyAncestor(hasTestTag("from-group"))).assertDoesNotExist()
        }

        rule.onNode(hasText("Share from") and hasAnyAncestor(hasTestTag("from-group"))).assertExists()
        rule.onNode(hasText("Share until") and hasAnyAncestor(hasTestTag("until-group"))).assertExists()
    }

    /** Each caption is a heading, so assistive tech can jump between the From and Until groups. */
    @Test
    fun `the group captions are accessibility headings`() {
        setChoices()
        rule.onNodeWithText("Share from").assertIsHeading()
        rule.onNodeWithText("Share until").assertIsHeading()
    }

    @Test
    fun `Now is disabled outside the event window, Event start stays enabled`() {
        setChoices(nowAvailable = false)
        rule.onNodeWithTag("from-now").assertIsNotEnabled()
        rule.onNodeWithTag("from-event-start").assertIsEnabled()
        rule.onNodeWithText("Same as the event start until the event begins.").assertExists()
    }

    @Test
    fun `selecting a From preset fires its callback`() {
        var picked: FromChoice? = null
        setChoices(nowAvailable = true, onFromSelect = { picked = it })
        rule.onNodeWithTag("from-now").assertIsEnabled().performClick()
        assertEquals(FromChoice.NOW, picked)
    }

    @Test
    fun `selecting an Until preset fires its callback`() {
        var picked: UntilChoice? = null
        setChoices(untilSelected = UntilChoice.CUSTOM, onUntilSelect = { picked = it })
        rule.onNodeWithTag("until-event-end").performClick()
        assertEquals(UntilChoice.EVENT_END, picked)
    }

    @Test
    fun `tapping From Custom opens the picker but does NOT select it`() {
        var selectedWith: FromChoice? = null
        setChoices(onFromSelect = { selectedWith = it })

        rule.onNodeWithText("Date & time").assertDoesNotExist()
        rule.onNodeWithTag("from-custom").performClick()

        rule.onNodeWithText("Date & time").assertExists()
        assertNull(selectedWith, "tapping Custom must not select it — the picker's OK does")
    }

    @Test
    fun `confirming the From picker commits a value inside the window`() {
        var picked: LocalDateTime? = null
        setChoices(fromCustomValue = null, onFromCustomPicked = { picked = it })

        rule.onNodeWithTag("from-custom").performClick()
        // Seeds at the window start; OK returns it, coerced into the window.
        rule.onNodeWithText("OK").performClick()

        val committed = picked
        assertTrue(committed != null && committed >= windowStart && committed <= windowEnd)
        assertEquals(windowStart, committed)
    }

    @Test
    fun `confirming the Until picker commits a value inside the window`() {
        var picked: LocalDateTime? = null
        setChoices(untilCustomValue = null, onUntilCustomPicked = { picked = it })

        rule.onNodeWithTag("until-custom").performClick()
        // Seeds at the window end; OK returns it, coerced into the window.
        rule.onNodeWithText("OK").performClick()

        assertEquals(windowEnd, picked)
    }

    @Test
    fun `cancelling the picker commits nothing and dismisses it`() {
        var fromPicked: LocalDateTime? = null
        setChoices(onFromCustomPicked = { fromPicked = it })

        rule.onNodeWithTag("from-custom").performClick()
        rule.onNodeWithText("Date & time").assertExists()
        rule.onNodeWithText("Cancel").performClick()

        rule.onNodeWithText("Date & time").assertDoesNotExist()
        assertNull(fromPicked, "a cancelled picker commits no bound")
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsRadio() =
    assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsHeading() =
    assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
