package app.snapsync.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The joined screen's explanation row and the invite's QR sheet (capabilities `sync-status`,
 * `manage-membership`): a caption's link is a real control that fires once, every state of a row renders, and
 * the QR stays dark on white in dark appearance.
 */
class AppExplainRowTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `a caption's link is its own control and fires its action`() {
        var clicks = 0
        rule.setContent {
            AppTheme(platformDates) {
                AppExplainRow(
                    subject = ExplainSubject.SHARING,
                    state = ExplainState.OFF,
                    title = "You're not sharing",
                    caption = "Turn on sharing in Settings to share photos.",
                    link = CaptionLink("Settings") { clicks++ },
                )
            }
        }
        rule.onNodeWithText("You're not sharing").assertExists()
        rule.onNodeWithText("Turn on sharing in Settings to share photos.").performFirstLinkClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `every subject renders in every state`() {
        rule.setContent {
            AppTheme(platformDates) {
                androidx.compose.foundation.layout.Column {
                    for (subject in ExplainSubject.entries) for (state in ExplainState.entries) {
                        AppExplainRow(subject = subject, state = state, title = "$subject $state", caption = "caption")
                    }
                }
            }
        }
        for (subject in ExplainSubject.entries) for (state in ExplainState.entries) {
            rule.onNodeWithText("$subject $state").assertExists()
        }
    }

    @Test
    fun `the QR sheet shows the code on white in dark appearance and closes on a swipe`() {
        var dismissed = 0
        rule.setContent {
            CompositionLocalProvider(LocalDarkThemeOverride provides true) {
                AppTheme(platformDates) {
                    AppQrSheet(
                        title = "Join Anna's Birthday",
                        content = "https://snapsync.app/e/00000000-0000-0000-0000-000000000000",
                        caption = "Let family and friends scan this with their camera",
                        onDismiss = { dismissed++ },
                    )
                }
            }
        }
        rule.onNodeWithText("Join Anna's Birthday").assertExists()
        // The caption sits on the code's card: the pixel just left of the caption's first glyph is the card's.
        val caption = rule.onNodeWithText("Let family and friends scan this with their camera")
        val pixels = caption.captureToImage().toPixelMap()
        assertEquals(Color.White, pixels[0, 0], "the QR card must stay white in dark appearance")

        caption.performTouchInput { swipeDown() }
        rule.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun `a caption that does not hold its link's words is shown whole, with no link`() {
        var clicks = 0
        val caption = "Turn on sharing to share photos."
        rule.setContent {
            AppTheme(platformDates) {
                AppExplainRow(
                    subject = ExplainSubject.SHARING,
                    state = ExplainState.OFF,
                    title = "You're not sharing",
                    caption = caption,
                    link = CaptionLink("Settings") { clicks++ },
                )
            }
        }
        val shown = rule.onNodeWithText(caption).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals(emptyList(), shown.getLinkAnnotations(0, shown.length))
        assertEquals(0, clicks)
    }
}
