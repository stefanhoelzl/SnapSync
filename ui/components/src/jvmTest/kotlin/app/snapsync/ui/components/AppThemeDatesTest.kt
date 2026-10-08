package app.snapsync.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.snapsync.model.DateFormats
import kotlinx.datetime.LocalDateTime
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Where the design system's dates come from (`docs/architecture.md`, "Localization"): [AppTheme] asks the platform
 * it is handed, and asks again when that changes; outside a theme nothing answers, and reading a date says so.
 */
class AppThemeDatesTest {

    private companion object {
        val AT = LocalDateTime(2026, 10, 5, 14, 30)
    }

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the theme renders dates through the platform it is handed, and follows a new one`() {
        var platform by mutableStateOf(platformDates)
        rule.setContent {
            AppTheme(platform) { Text(appDateLabel(AT)) }
        }
        rule.onNodeWithText("5 Oct 2026").assertExists()
        platform = { DateFormats { _, _ -> "another platform's date" } }
        rule.onNodeWithText("another platform's date").assertExists()
    }

    @Test
    fun `a date read outside a theme fails, naming what is missing`() {
        val failure = assertFailsWith<IllegalStateException> {
            rule.setContent { Text(appDateLabel(AT)) }
        }
        assertEquals(
            "no DateFormats provided — AppTheme provides the platform's, a test provides its own",
            failure.message,
        )
    }
}
